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
            long pollIntervalMs
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
     * Transactional outbox transport. The verdict is committed together with the outbox row;
     * this block only controls how that row reaches the broker.
     */
    public record Mq(
            boolean enabled,
            String namesrv,
            String topic,
            String producerGroup,
            String consumerGroup,
            int maxAttempts
    ) {
    }
}
