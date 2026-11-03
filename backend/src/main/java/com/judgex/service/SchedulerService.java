package com.judgex.service;

import com.judgex.config.JudgexProps;
import com.judgex.repo.SubmissionRepo;
import com.judgex.repo.TaskRepo;
import com.judgex.repo.WorkerRepo;
import com.judgex.redis.PresenceService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pull-based scheduler living inside the app JVM.
 *
 * Design notes that matter in an interview:
 *  - Workers PULL. Nothing is pushed at a worker, so a paused/killed worker
 *    cannot accumulate assignments it will never run.
 *  - Claiming is a compare-and-set UPDATE, so N workers can race safely.
 *  - A lease + renewal replaces "is the worker alive?" with "is this task still
 *    being worked on?", which survives a network partition too.
 */
@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);
    private static final int MAX_ATTEMPTS = 3;

    private final TaskRepo tasks;
    private final SubmissionRepo submissions;
    private final WorkerRepo workers;
    private final JudgeEngine engine;
    private final ProgressHub hub;
    private final JudgexProps props;
    private final PresenceService presence;

    private final Semaphore slots;
    private final AtomicInteger active = new AtomicInteger();
    private final ExecutorService pool;
    private final String workerId;
    private final String host;

    public SchedulerService(TaskRepo tasks, SubmissionRepo submissions, WorkerRepo workers,
                            JudgeEngine engine, ProgressHub hub, JudgexProps props,
                            PresenceService presence) {
        this.tasks = tasks;
        this.submissions = submissions;
        this.workers = workers;
        this.engine = engine;
        this.hub = hub;
        this.props = props;
        this.presence = presence;

        int concurrency = Math.max(1, props.worker().concurrency());
        this.slots = new Semaphore(concurrency);
        this.pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r);
            t.setName("judge-worker-" + t.getId());
            t.setDaemon(true);
            return t;
        });
        this.workerId = props.worker().id();
        this.host = resolveHost();
        log.info("scheduler started workerId={} concurrency={} sandbox={}",
                workerId, concurrency, props.sandbox().mode());
    }

    @Scheduled(fixedDelayString = "${judgex.worker.heartbeat-interval-ms:3000}")
    public void heartbeat() {
        if (!props.worker().enabled()) {
            return;
        }
        workers.heartbeat(workerId, host, props.worker().concurrency(), active.get());
        // Same fact, two audiences: the table is the durable record, the TTL key is the
        // "who is alive right now" answer that needs no sweep.
        presence.beat(workerId, host, props.worker().concurrency(), active.get());
        int renewed = tasks.renewLeases(workerId, props.worker().leaseSeconds());
        if (renewed > 0) {
            log.debug("renewed {} leases for {}", renewed, workerId);
        }
    }

    @Scheduled(fixedDelayString = "${judgex.worker.poll-interval-ms:250}")
    public void poll() {
        if (!props.worker().enabled()) {
            return;
        }
        while (slots.tryAcquire()) {
            boolean claimed = false;
            try {
                List<Long> candidates = tasks.candidates(1);
                for (Long taskId : candidates) {
                    if (tasks.tryClaim(taskId, workerId, props.worker().leaseSeconds())) {
                        TaskRepo.ClaimedTask task = tasks.claimed(taskId);
                        active.incrementAndGet();
                        claimed = true;
                        pool.execute(() -> runTask(task));
                        break;
                    }
                }
            } catch (Exception e) {
                log.error("poll loop error: {}", e.toString(), e);
            } finally {
                if (!claimed) {
                    slots.release();
                }
            }
            if (!claimed) {
                return;
            }
        }
    }

    private void runTask(TaskRepo.ClaimedTask task) {
        try {
            engine.judge(task.submissionId());
            tasks.complete(task.id());
        } catch (Exception e) {
            log.error("task {} failed: {}", task.id(), e.toString(), e);
            tasks.deadLetter(task.id(), e.getMessage());
        } finally {
            active.decrementAndGet();
            slots.release();
        }
    }

    /**
     * Requeue tasks whose owner stopped renewing its lease. Bounded retries turn
     * a permanently broken submission into a visible DEAD task instead of an
     * infinite retry loop.
     */
    @Scheduled(fixedDelay = 5000)
    public void reclaim() {
        if (!props.worker().enabled()) {
            return;
        }
        List<TaskRepo.Lease> expired = tasks.expiredLeases(20);
        for (TaskRepo.Lease lease : expired) {
            if (lease.attempts() >= MAX_ATTEMPTS) {
                tasks.deadLetter(lease.taskId(), "lease expired after " + lease.attempts() + " attempts");
                submissions.finish(lease.submissionId(), "DONE", "SYSTEM_ERROR", 0,
                        "judge node lost after " + lease.attempts() + " attempts", 0);
                hub.finish(lease.submissionId(), Map.of(
                        "row", submissions.find(lease.submissionId()).orElse(null),
                        "cases", List.of(),
                        "compileOutput", ""));
                log.warn("task {} dead-lettered after {} attempts", lease.taskId(), lease.attempts());
                continue;
            }
            if (tasks.requeue(lease.taskId())) {
                submissions.resetForRetry(lease.submissionId());
                hub.publish(lease.submissionId(), "status",
                        Map.of("status", "QUEUED", "submissionId", lease.submissionId(), "reason", "lease expired"));
                log.warn("reclaimed task {} (submission {}) attempt {}", lease.taskId(), lease.submissionId(),
                        lease.attempts());
            }
        }
        workers.prune(60_000);
    }

    @Scheduled(fixedDelay = 20000)
    public void sweep() {
        hub.sweep();
    }

    private String resolveHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    public int activeTasks() {
        return active.get();
    }

    public String workerId() {
        return workerId;
    }

    @PreDestroy
    public void shutdown() {
        log.info("scheduler stopping, waiting for in-flight judges");
        pool.shutdown();
        try {
            if (!pool.awaitTermination(20, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}
