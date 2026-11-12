package com.judgex.mq;

import com.judgex.config.JudgexProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Drains the outbox into RocketMQ.
 *
 * <p>Ordering guarantees we rely on: a row is claimed with a CAS update and leased for 30s,
 * so a crashed relay cannot cause a second send; a duplicate send would in any case be
 * absorbed by the consumer's idempotency key.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH = 20;
    private static final int LEASE_SECONDS = 30;

    private final OutboxMapper outbox;
    private final MqProducer producer;
    private final JudgexProps props;

    public OutboxRelay(OutboxMapper outbox, MqProducer producer, JudgexProps props) {
        this.outbox = outbox;
        this.producer = producer;
        this.props = props;
    }

    @Scheduled(fixedDelay = 400)
    public void tick() {
        if (!props.mq().enabled()) {
            return;
        }
        // Cheap when already connected; throttled to one attempt per 15s when the broker is down.
        producer.get();
        int reclaimed = outbox.reclaimExpired();
        if (reclaimed > 0) {
            log.warn("reclaimed {} outbox rows whose relay lease expired", reclaimed);
        }
        List<OutboxMapper.Due> batch = outbox.due(BATCH);
        for (OutboxMapper.Due row : batch) {
            if (!outbox.claim(row.id(), LocalDateTime.now().plusSeconds(LEASE_SECONDS))) {
                continue;
            }
            try {
                producer.send(row.topic(), row.eventKey(), row.payload());
                outbox.sent(row.id());
            } catch (Exception e) {
                int attempts = row.attempts() + 1;
                boolean dead = attempts >= props.mq().maxAttempts();
                long backoffMs = Math.min(30_000L, 1000L << Math.min(attempts, 5));
                outbox.failed(row.id(), dead, e.getMessage(),
                        LocalDateTime.now().plusNanos(backoffMs * 1_000_000L));
                log.warn("outbox {} send failed (attempt {}/{}), {}ms backoff: {}",
                        row.eventKey(), attempts, props.mq().maxAttempts(), backoffMs, e.toString());
            }
        }
    }
}
