package com.judgex.mq;

import com.judgex.config.JudgexProps;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Thin wrapper around {@link DefaultMQProducer}.
 *
 * <p>The producer is started lazily and never blocks a judgement: if the broker is down the
 * relay simply keeps rows in the outbox and retries later. That is the whole point of the
 * outbox pattern — the database is the source of truth, the broker is a delivery mechanism.
 */
@Component
public class MqProducer {

    private static final Logger log = LoggerFactory.getLogger(MqProducer.class);

    private final JudgexProps props;
    private volatile DefaultMQProducer producer;
    /** IDLE = enabled but not started yet; the first send or the warm-up thread starts it. */
    private volatile String state = "IDLE";
    private volatile String lastError;
    /** A failed start blocks for seconds; do not retry on every relay tick. */
    private volatile long nextAttemptAt;
    /** True while a start attempt is in flight, so callers fail fast instead of waiting. */
    private volatile boolean starting;

    public MqProducer(JudgexProps props) {
        this.props = props;
    }

    /** Warm the connection off the startup thread so the scheduler never blocks on TCP. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!props.mq().enabled()) {
            return;
        }
        Thread t = new Thread(this::get, "mq-producer-warmup");
        t.setDaemon(true);
        t.start();
    }

    public DefaultMQProducer get() {
        if (!props.mq().enabled()) {
            state = "DISABLED";
            return null;
        }
        DefaultMQProducer ready = producer;
        if (ready != null) {
            return ready;
        }
        // Starting a producer takes seconds; never park a request thread (or the dispatch
        // worker) behind it. A dropped ping is covered by the fallback poll.
        if (starting) {
            return null;
        }
        synchronized (this) {
            if (producer != null) {
                return producer;
            }
            if (starting || System.currentTimeMillis() < nextAttemptAt) {
                return null;
            }
            starting = true;
            nextAttemptAt = System.currentTimeMillis() + 15_000L;
            try {
                DefaultMQProducer p = new DefaultMQProducer(props.mq().producerGroup());
                p.setNamesrvAddr(props.mq().namesrv());
                p.setSendMsgTimeout(3000);
                p.setRetryTimesWhenSendFailed(1);
                p.start();
                producer = p;
                state = "UP";
                lastError = null;
                log.info("rocketmq producer up, namesrv={}, group={}", props.mq().namesrv(), props.mq().producerGroup());
                warmRoutes(p);
            } catch (Exception e) {
                state = "DOWN";
                lastError = rootMessage(e);
                log.warn("rocketmq producer unavailable: {}", e.toString());
            } finally {
                starting = false;
            }
            return producer;
        }
    }

    /**
     * Fetch topic routes up front. The first send otherwise pays a name-server round trip
     * plus a broker handshake — measured at ~150 ms on this machine — which is exactly the
     * cold-start hiccup that makes "millisecond wake-up" look untrue for the first
     * submission after a restart.
     */
    private void warmRoutes(DefaultMQProducer p) {
        // Only the wake-up topic: it is on the hot path of every submission. The lease
        // topic is written twice per judgement at most and its messages are delayed anyway.
        warmRoute(p, props.mq().taskTopic(), 0);
    }

    /**
     * One no-op message per topic. The consumer ignores a submissionId of 0, so this only
     * fills the route cache and opens the broker connection ahead of the first real task.
     */
    private void warmRoute(DefaultMQProducer p, String topic, int delayLevel) {
        try {
            Message m = new Message(topic, "{\"submissionId\":0,\"ts\":0}".getBytes(StandardCharsets.UTF_8));
            m.setKeys("warmup");
            if (delayLevel > 0) {
                m.setDelayTimeLevel(delayLevel);
            }
            p.send(m);
            log.debug("warmed route for topic {}", topic);
        } catch (Exception e) {
            log.debug("route warm-up for {} skipped: {}", topic, e.toString());
        }
    }

    public void send(String topic, String key, String payload) throws Exception {
        send(topic, key, payload, 0);
    }    /**
     * Delay-level send. Used for lease alarms: the message arrives when the lease should
     * have expired, and the receiver decides from the database whether it still matters.
     */
    public void sendDelay(String topic, String key, String payload, int delayLevel) throws Exception {
        send(topic, key, payload, delayLevel);
    }

    private void send(String topic, String key, String payload, int delayLevel) throws Exception {
        DefaultMQProducer p = get();
        if (p == null) {
            throw new IllegalStateException("rocketmq producer unavailable: " + lastError);
        }
        Message msg = new Message(topic, payload.getBytes(StandardCharsets.UTF_8));
        msg.setKeys(key);
        if (delayLevel > 0) {
            msg.setDelayTimeLevel(delayLevel);
        }
        try {
            p.send(msg);
            if (!"UP".equals(state)) {
                log.info("rocketmq producer recovered");
            }
            state = "UP";
            lastError = null;
        } catch (Exception e) {
            // Broker reachable from the producer's point of view, but this send failed
            // (route missing, timeout, ...). Surface it instead of claiming UP.
            state = "DEGRADED";
            lastError = rootMessage(e);
            throw e;
        }
    }

    @PreDestroy
    public void stop() {
        DefaultMQProducer p = producer;
        if (p != null) {
            p.shutdown();
        }
    }

    public String status() {
        return state;
    }

    public String lastError() {
        return lastError;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : m;
    }
}
