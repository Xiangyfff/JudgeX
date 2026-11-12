package com.judgex.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.judgex.config.JudgexProps;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fire-and-forget pings to the broker. Two kinds:
 *
 * <ul>
 *   <li>{@code judgex-task} — "there is work in the queue", sent after a submission commits.
 *       It replaces the 250ms poll with an event; an idle node sleeps until this arrives.</li>
 *   <li>{@code judgex-lease} — a delayed message that comes back when a lease should have
 *       expired. It replaces the 5s full-table sweep for dead workers.</li>
 * </ul>
 *
 * <p>Both are lossy on purpose. The database is the only source of truth: a dropped ping
 * costs one fallback poll (at most {@code idle-poll-ms}), a dropped alarm costs one safety
 * sweep. Neither can duplicate or lose a verdict, because claiming is still a CAS UPDATE.
 *
 * <p>Sending is off the request thread: a broker that is down makes {@code send} block for
 * the producer timeout, and a submission must not wait three seconds for a ping.
 */
@Component
public class TaskDispatch {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatch.class);

    private final JudgexProps props;
    private final MqProducer producer;
    private final ObjectMapper mapper;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mq-dispatch");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong pings = new AtomicLong();
    private final AtomicLong alarms = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public TaskDispatch(JudgexProps props, MqProducer producer, ObjectMapper mapper) {
        this.props = props;
        this.producer = producer;
        this.mapper = mapper;
    }

    /**
     * Tell the cluster a task is claimable. Called inside the submission transaction, so the
     * ping is deferred to after commit — waking a worker before the row is visible would just
     * make it find nothing and go back to sleep.
     */
    public void taskQueued(long submissionId) {
        if (!props.mq().enabled()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ping(submissionId);
                }
            });
        } else {
            ping(submissionId);
        }
    }

    private void ping(long submissionId) {
        pings.incrementAndGet();
        async(() -> producer.send(props.mq().taskTopic(), "task-" + submissionId, payload(0, submissionId)));
    }

    /** Arm (or re-arm) the alarm for one lease. */
    public void leaseAlarm(long taskId, long submissionId, int delayLevel) {
        if (!props.mq().enabled()) {
            return;
        }
        alarms.incrementAndGet();
        async(() -> producer.sendDelay(props.mq().leaseTopic(), "lease-" + taskId,
                payload(taskId, submissionId), delayLevel));
    }

    /** taskId is 0 for wake-up pings: the consumer ignores it, only the alarm needs it. */
    private String payload(long taskId, long submissionId) {
        ObjectNode node = mapper.createObjectNode();
        if (taskId > 0) {
            node.put("taskId", taskId);
        }
        node.put("submissionId", submissionId);
        return node.toString();
    }

    private void async(ThrowingRunnable action) {
        sender.execute(() -> {
            try {
                action.run();
            } catch (Exception e) {
                // Expected when the broker is down; the fallback paths cover it.
                dropped.incrementAndGet();
                log.debug("mq ping dropped: {}", e.toString());
            }
        });
    }

    public long sentPings() {
        return pings.get();
    }

    public long sentAlarms() {
        return alarms.get();
    }

    public long dropped() {
        return dropped.get();
    }

    @PreDestroy
    public void shutdown() {
        sender.shutdownNow();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
