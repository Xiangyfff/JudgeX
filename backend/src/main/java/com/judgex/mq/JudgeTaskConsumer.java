package com.judgex.mq;

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

import java.util.List;

/**
 * Turns "a task is queued" into "the dispatcher stops sleeping".
 *
 * <p>It carries no work, only a nudge: the task itself is still claimed with a CAS UPDATE
 * from the database. So a duplicate nudge is harmless and a lost one costs one fallback
 * poll — which is why this consumer always acknowledges, even if it cannot parse the body.
 */
@Component
public class JudgeTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(JudgeTaskConsumer.class);

    private final JudgexProps props;
    private final SchedulerService scheduler;

    private volatile DefaultMQPushConsumer consumer;
    private volatile String state = "DISABLED";

    public JudgeTaskConsumer(JudgexProps props, SchedulerService scheduler) {
        this.props = props;
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        start();
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
            DefaultMQPushConsumer c = new DefaultMQPushConsumer(props.mq().dispatchGroup());
            c.setNamesrvAddr(props.mq().namesrv());
            c.subscribe(props.mq().taskTopic(), "*");
            c.registerMessageListener((MessageListenerConcurrently) this::consume);
            c.start();
            consumer = c;
            state = "UP";
            log.info("dispatch consumer up, topic={}, group={}", props.mq().taskTopic(), props.mq().dispatchGroup());
        } catch (Exception e) {
            state = "DOWN";
            log.warn("dispatch consumer unavailable, will retry: {}", e.toString());
        }
    }

    private ConsumeConcurrentlyStatus consume(List<MessageExt> messages,
                                              org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext ctx) {
        for (MessageExt message : messages) {
            log.debug("wake-up for task message {}", message.getMsgId());
            scheduler.wakeUp();
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
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
