package com.judgex.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judgex.config.JudgexProps;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Downstream of the outbox: turns "submission judged" events into user-visible notifications.
 *
 * <p>This is deliberately a side channel — if the consumer is down the platform still works,
 * the outbox just keeps the events until it is back.
 */
@Component
public class JudgeResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(JudgeResultConsumer.class);

    private final JudgexProps props;
    private final OutboxMapper outbox;
    private final ObjectMapper mapper;

    private volatile DefaultMQPushConsumer consumer;
    private volatile String state = "DISABLED";

    public JudgeResultConsumer(JudgexProps props, OutboxMapper outbox, ObjectMapper mapper) {
        this.props = props;
        this.outbox = outbox;
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
            DefaultMQPushConsumer c = new DefaultMQPushConsumer(props.mq().consumerGroup());
            c.setNamesrvAddr(props.mq().namesrv());
            c.subscribe(props.mq().topic(), "*");
            c.registerMessageListener((MessageListenerConcurrently) this::consume);
            c.start();
            consumer = c;
            state = "UP";
            log.info("rocketmq consumer up, topic={}, group={}", props.mq().topic(), props.mq().consumerGroup());
        } catch (Exception e) {
            state = "DOWN";
            log.warn("rocketmq consumer unavailable, will retry: {}", e.toString());
        }
    }

    private ConsumeConcurrentlyStatus consume(List<MessageExt> messages,
                                              org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext ctx) {
        boolean retry = false;
        for (MessageExt message : messages) {
            try {
                JsonNode node = mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
                long submissionId = node.path("submissionId").asLong();
                long userId = node.path("userId").asLong();
                String verdict = node.path("verdict").asText("NONE");
                String userName = node.path("userName").asText("某位同学");
                String title = node.path("problemTitle").asText("题目 " + node.path("problemId").asLong());
                int score = node.path("score").asInt();
                long totalMs = node.path("totalMs").asLong();
                String text = "%s 的「%s」判定 %s · %d 分 · %dms".formatted(userName, title, verdict, score, totalMs);
                outbox.addNotification(submissionId, userId, verdict, text);
            } catch (JsonProcessingException e) {
                // A malformed payload can never succeed on redelivery: log it and drop it so the
                // queue is not poisoned by one bad message.
                log.error("malformed message {}, skipped: {}", message.getMsgId(), e.toString());
            } catch (Exception e) {
                // The database is the retryable part. Redelivery is safe: the notification table
                // has a unique key on submission_id, so a duplicate insert is absorbed.
                log.error("cannot consume message {}, will retry: {}", message.getMsgId(), e.toString());
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
