package com.judgex.redis;

import com.judgex.config.JudgexProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Single place that decides whether Redis is usable right now.
 * <p>
 * Every caller has a database-backed or in-memory fallback, so Redis is an accelerator here,
 * never a source of truth. When the server goes away we keep answering requests; we just stop
 * paying for the round trip. A failed ping parks the gate for five seconds so a dead Redis
 * cannot turn every submission into an 800ms timeout.
 */
@Component
public class RedisGate {

    private static final Logger log = LoggerFactory.getLogger(RedisGate.class);
    private static final long COOLDOWN_MS = 5_000;

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private volatile long downUntil;
    private volatile String lastError;

    public RedisGate(StringRedisTemplate redis, JudgexProps props) {
        this.redis = redis;
        this.enabled = props.redis() != null && props.redis().enabled();
    }

    /** True when the caller may issue a Redis command without paying for a hang. */
    public boolean up() {
        if (!enabled) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now < downUntil) {
            return false;
        }
        try {
            String pong = redis.execute((RedisCallback<String>) connection -> connection.ping());
            return pong != null;
        } catch (Exception e) {
            downUntil = now + COOLDOWN_MS;
            lastError = e.getMessage();
            log.warn("redis unavailable, degrading to local fallback for {}ms: {}", COOLDOWN_MS, e.getMessage());
            return false;
        }
    }

    public String status() {
        if (!enabled) {
            return "DISABLED";
        }
        return up() ? "UP" : "DOWN";
    }

    public String lastError() {
        return lastError;
    }

    public StringRedisTemplate template() {
        return redis;
    }
}
