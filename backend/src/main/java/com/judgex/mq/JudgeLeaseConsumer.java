package com.judgex.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judgex.config.JudgexProps;
import com.judgex.service.SchedulerService;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Receives the delayed "lease is due" alarm and asks the scheduler to look at that one task.
 *
 * <p>The message is only a reminder, never an instruction: the receiver re-reads the task
 * row and decides. A duplicate delivery is therefore a no-op, a stale one is a no-op, and
 * a lost one is covered by the slow safety sweep.
 */
@Component
public class JudgeLeaseConsumer {

    private static final Logger log = LoggerFactory.getLogger(JudgeLeaseConsumer.class);

    private final JudgexProps props;
    private final SchedulerService scheduler;
    private final ObjectMapper mapper;

    private volatile DefaultMQPushConsumer consumer;
    private volatile String state = "DISABLED";

    public JudgeLeaseConsumer(JudgexProps props, SchedulerService scheduler, ObjectMapper mapper) {
        this.props = props;
        this.scheduler = scheduler;
        this.mapper = mapper;
    }

    /**
     * Start on a background thread: the broker handshake can take seconds on a cold JVM,
     * and blocking the ready event would keep the dispatcher (started by another listener)
     * from running until it finishes.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread t = new Thread(this::start, "mq-consumer-start");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(fixedDelay = 15_000)
    public void ensureStarted() {
        if (props.mq().enabled() && consumer == null) {
            start();
        }
    }

    private synchronized void start() {
        if (!props.mq().enabled() || consumer != null) {
            return;
        }
        try {
            DefaultMQPushConsumer c = new DefaultMQPushConsumer(props.mq().leaseGroup());
            c.setNamesrvAddr(props.mq().namesrv());
            c.subscribe(props.mq().leaseTopic(), "*");
            c.registerMessageListener((MessageListenerConcurrently) this::consume);
            c.start();
            consumer = c;
            state = "UP";
            log.info("lease consumer up, topic={}, group={}", props.mq().leaseTopic(), props.mq().leaseGroup());
        } catch (Exception e) {
            state = "DOWN";
            log.warn("lease consumer unavailable, will retry: {}", e.toString());
        }
    }

    private ConsumeConcurrentlyStatus consume(List<MessageExt> messages,
                                              org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext ctx) {
        boolean retry = false;
        for (MessageExt message : messages) {
            try {
                JsonNode node = mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
                long taskId = node.path("taskId").asLong();
                if (taskId <= 0) {
                    log.warn("lease alarm without taskId, skipped: {}", message.getMsgId());
                    continue;
                }
                scheduler.onLeaseAlarm(taskId);
            } catch (Exception e) {
                // The read is cheap and idempotent, so redelivery is the right answer here:
                // a transient database error must not silently drop a lease check.
                log.warn("cannot handle lease alarm {}, will retry: {}", message.getMsgId(), e.toString());
                retry = true;
            }
        }
        return retry ? ConsumeConcurrentlyStatus.RECONSUME_LATER : ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    @PreDestroy
    public void stop() {
        DefaultMQPushConsumer c = consumer;
        if (c != null) {
            c.shutdown();
        }
    }

    public String status() {
        return state;
    }
}
