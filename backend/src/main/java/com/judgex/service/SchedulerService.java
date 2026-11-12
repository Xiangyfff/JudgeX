package com.judgex.service;

import com.judgex.config.JudgexProps;
import com.judgex.mq.TaskDispatch;
import com.judgex.repo.SubmissionMapper;
import com.judgex.repo.TaskMapper;
import com.judgex.repo.WorkerMapper;
import com.judgex.redis.PresenceService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDateTime;
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
 * <p>Design notes that matter in an interview:
 * <ul>
 *   <li>Workers PULL. Nothing is pushed at a worker, so a paused/killed worker cannot
 *       accumulate assignments it will never run.</li>
 *   <li>Claiming is a compare-and-set UPDATE, so N workers can race safely — and the
 *       message that wakes them up is only a hint, never an assignment.</li>
 *   <li>A lease + renewal replaces "is the worker alive?" with "is this task still being
 *       worked on?", which survives a network partition too.</li>
 *   <li>The loop no longer polls every 250ms: it sleeps on a semaphore and is woken by a
 *       queue message. The timeout on that wait is the fallback for a dead broker.</li>
 * </ul>
 */
@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);
    private static final int MAX_ATTEMPTS = 3;

    /** RocketMQ delay levels, in seconds, index 0 == level 1. */
    private static final long[] DELAY_LEVEL_SECONDS = {
            1, 5, 10, 30, 60, 120, 180, 240, 300, 360, 420, 480, 540, 600, 1200, 1800, 3600, 7200
    };

    private final TaskMapper tasks;
    private final SubmissionMapper submissions;
    private final WorkerMapper workers;
    private final JudgeEngine engine;
    private final ProgressHub hub;
    private final JudgexProps props;
    private final PresenceService presence;
    private final TaskDispatch dispatch;

    private final Semaphore slots;
    /** Released by the dispatch consumer; one permit is enough to mean "go look". */
    private final Semaphore wake = new Semaphore(0);
    private final AtomicInteger active = new AtomicInteger();
    private final ExecutorService pool;
    private final String workerId;
    private final String host;
    private volatile boolean running = true;
    private Thread pollThread;

    public SchedulerService(TaskMapper tasks, SubmissionMapper submissions, WorkerMapper workers,
                            JudgeEngine engine, ProgressHub hub, JudgexProps props,
                            PresenceService presence, TaskDispatch dispatch) {
        this.tasks = tasks;
        this.submissions = submissions;
        this.workers = workers;
        this.engine = engine;
        this.hub = hub;
        this.props = props;
        this.presence = presence;
        this.dispatch = dispatch;

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

    @EventListener(ApplicationReadyEvent.class)
    public void startDispatcher() {
        if (!props.worker().enabled() || pollThread != null) {
            return;
        }
        pollThread = new Thread(this::pollLoop, "judge-dispatcher");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    /**
     * Called by the dispatch consumer when a task is queued (or requeued). Only one permit
     * is kept: ten messages arriving together must not cause ten consecutive scans.
     */
    public void wakeUp() {
        if (wake.availablePermits() == 0) {
            wake.release();
        }
    }

    private void pollLoop() {
        long idle = Math.max(200, props.worker().idlePollMs());
        while (running) {
            try {
                // Wake on a message; the timeout is the "broker is down" fallback.
                wake.tryAcquire(idle, TimeUnit.MILLISECONDS);
                drain();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("dispatch loop error: {}", e.toString(), e);
            }
        }
    }

    /** Claim until this node has no free slot or the queue is empty. */
    private void drain() {
        if (!props.worker().enabled()) {
            return;
        }
        while (slots.tryAcquire()) {
            boolean claimed = false;
            try {
                List<Long> candidates = tasks.candidates(1);
                for (Long taskId : candidates) {
                    if (tasks.tryClaim(taskId, workerId, props.worker().leaseSeconds())) {
                        TaskMapper.ClaimedTask task = tasks.claimed(taskId);
                        active.incrementAndGet();
                        claimed = true;
                        // Arm the alarm before the work starts: if this JVM dies now, the
                        // broker still holds a message that will bring the task back.
                        dispatch.leaseAlarm(task.id(), task.submissionId(), props.mq().leaseDelayLevel());
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

    private void runTask(TaskMapper.ClaimedTask task) {
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

    /**
     * A delayed alarm came back for one task. Decide from the database, not from the message:
     * still assigned and lease still live => re-arm for the remainder; lease over => reclaim.
     */
    public void onLeaseAlarm(long taskId) {
        if (!props.worker().enabled()) {
            return;
        }
        TaskMapper.TaskState state = tasks.selectState(taskId);
        if (state == null || !"ASSIGNED".equals(state.status()) || state.leaseExpiresAt() == null) {
            // Finished, requeued or dead already: the alarm was only ever a reminder.
            return;
        }
        long remainingMs = Duration.between(LocalDateTime.now(), state.leaseExpiresAt()).toMillis();
        if (remainingMs > 1000) {
            dispatch.leaseAlarm(taskId, state.submissionId(), delayLevelFor(remainingMs));
            return;
        }
        expire(new TaskMapper.Lease(taskId, state.submissionId(), state.attempts()));
    }

    /**
     * Slow safety net for alarms that never arrived (broker was down when it was armed).
     * If this finds anything it means the fast path leaked, so it says so.
     */
    @Scheduled(fixedDelayString = "${judgex.worker.safety-sweep-ms:60000}")
    public void reclaim() {
        if (!props.worker().enabled()) {
            return;
        }
        List<TaskMapper.Lease> expired = tasks.expiredLeases(20);
        if (!expired.isEmpty()) {
            log.warn("safety sweep found {} expired lease(s); delay alarms should have handled them", expired.size());
        }
        for (TaskMapper.Lease lease : expired) {
            expire(lease);
        }
        workers.prune(60_000);
    }

    /**
     * Requeue a task whose owner stopped renewing its lease. Bounded retries turn a
     * permanently broken submission into a visible DEAD task instead of an endless loop.
     */
    private void expire(TaskMapper.Lease lease) {
        if (lease.attempts() >= MAX_ATTEMPTS) {
            tasks.deadLetter(lease.taskId(), "lease expired after " + lease.attempts() + " attempts");
            submissions.finish(lease.submissionId(), "DONE", "SYSTEM_ERROR", 0,
                    "judge node lost after " + lease.attempts() + " attempts", 0);
            hub.finish(lease.submissionId(), Map.of(
                    "row", submissions.find(lease.submissionId()).orElse(null),
                    "cases", List.of(),
                    "compileOutput", ""));
            log.warn("task {} dead-lettered after {} attempts", lease.taskId(), lease.attempts());
            return;
        }
        if (tasks.requeue(lease.taskId())) {
            submissions.resetForRetry(lease.submissionId());
            hub.publish(lease.submissionId(), "status",
                    Map.of("status", "QUEUED", "submissionId", lease.submissionId(), "reason", "lease expired"));
            // Same ping as a fresh submission: the task is claimable again.
            dispatch.taskQueued(lease.submissionId());
            log.warn("reclaimed task {} (submission {}) attempt {}", lease.taskId(), lease.submissionId(),
                    lease.attempts());
        }
    }

    /** Smallest delay level that does not overshoot the remaining lease too far. */
    private static int delayLevelFor(long remainingMs) {
        long seconds = Math.max(1, remainingMs / 1000);
        int best = 1;
        for (int i = 0; i < DELAY_LEVEL_SECONDS.length; i++) {
            if (DELAY_LEVEL_SECONDS[i] <= seconds) {
                best = i + 1;
            } else {
                break;
            }
        }
        return best;
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
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
        }
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
