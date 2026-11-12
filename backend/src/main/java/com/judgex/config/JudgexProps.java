package com.judgex.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly typed view over the "judgex.*" configuration block.
 */
@ConfigurationProperties(prefix = "judgex")
public record JudgexProps(
        String jwtSecret,
        int tokenTtlHours,
        Worker worker,
        Sandbox sandbox,
        Limits limits,
        Redis redis,
        Mq mq
) {
    public record Worker(
            String id,
            int concurrency,
            boolean enabled,
            long heartbeatIntervalMs,
            long leaseSeconds,
            /**
             * Only a fallback: the dispatcher is woken by a message. If the broker is
             * unreachable this is how long an idle node waits before looking anyway.
             */
            long idlePollMs,
            /** Very slow second net for leases whose alarm message never arrived. */
            long safetySweepMs
    ) {
    }

    public record Sandbox(
            String mode,
            int maxOutputBytes,
            String workDir,
            String dockerImage
    ) {
    }

    public record Limits(
            int maxSourceBytes,
            int maxActivePerUser,
            int submitRatePerMinute
    ) {
    }

    /** Ephemeral state only: rate-limit buckets, worker presence, ranking. Never tasks. */
    public record Redis(
            boolean enabled,
            String keyPrefix
    ) {
    }

    /**
     * One broker, three jobs. Only the notification path needs the outbox: a lost
     * "there is work" ping or a lost lease alarm costs a few seconds of latency,
     * while a lost notification is a verdict the user never sees.
     */
    public record Mq(
            boolean enabled,
            String namesrv,
            /** Notifications, delivered from the outbox. */
            String topic,
            /** "A task is queued" wake-up pings. Lossy on purpose. */
            String taskTopic,
            /** "This lease is about to expire" alarms. Lossy on purpose. */
            String leaseTopic,
            String producerGroup,
            String consumerGroup,
            String dispatchGroup,
            String leaseGroup,
            /** RocketMQ delay level 4 = 30s; the alarm re-arms itself if the lease is still live. */
            int leaseDelayLevel,
            int maxAttempts
    ) {
    }
}
