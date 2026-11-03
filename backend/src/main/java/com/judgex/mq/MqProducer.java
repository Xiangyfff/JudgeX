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
    private volatile String state = "DISABLED";
    private volatile String lastError;
    /** A failed start blocks for seconds; do not retry on every relay tick. */
    private volatile long nextAttemptAt;

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

    public synchronized DefaultMQProducer get() {
        if (!props.mq().enabled()) {
            state = "DISABLED";
            return null;
        }
        if (producer != null) {
            return producer;
        }
        if (System.currentTimeMillis() < nextAttemptAt) {
            return null;
        }
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
        } catch (Exception e) {
            state = "DOWN";
            lastError = rootMessage(e);
            log.warn("rocketmq producer unavailable: {}", e.toString());
        }
        return producer;
    }

    public void send(String topic, String key, String payload) throws Exception {
        DefaultMQProducer p = get();
        if (p == null) {
            throw new IllegalStateException("rocketmq producer unavailable: " + lastError);
        }
        Message msg = new Message(topic, payload.getBytes(StandardCharsets.UTF_8));
        msg.setKeys(key);
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
