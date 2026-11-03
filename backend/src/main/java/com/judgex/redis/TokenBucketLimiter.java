package com.judgex.redis;

import com.judgex.common.ApiException;
import com.judgex.config.JudgexProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token bucket shared by every application instance.
 * <p>
 * The in-process version can only see its own counters: run three replicas and a user gets
 * three times the allowance. Pushing the bucket into Redis fixes that, and the Lua script
 * fixes the read-modify-write race without a distributed lock.
 * <p>
 * State lives in a hash with a short TTL. Losing it costs a user a few tokens, nothing more —
 * which is exactly why this is allowed to be ephemeral.
 */
@Component
public class TokenBucketLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketLimiter.class);

    /**
     * KEYS[1] bucket, ARGV: capacity, refill-per-second, now-ms, ttl-ms.
     * Returns 1 when a token was taken, 0 when the caller must wait.
     */
    private static final String LUA = """
            local key      = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill   = tonumber(ARGV[2])
            local now      = tonumber(ARGV[3])
            local ttl      = tonumber(ARGV[4])
            local data     = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens   = tonumber(data[1])
            local ts       = tonumber(data[2])
            if tokens == nil or ts == nil then
              tokens = capacity
              ts = now
            end
            local delta = math.max(0, now - ts) / 1000.0
            tokens = math.min(capacity, tokens + delta * refill)
            local allowed = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end
            redis.call('HMSET', key, 'tokens', tokens, 'ts', now)
            redis.call('PEXPIRE', key, ttl)
            return allowed
            """;

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(LUA, Long.class);

    private final RedisGate gate;
    private final String prefix;
    private final Map<Long, Deque<Long>> localWindows = new ConcurrentHashMap<>();

    public TokenBucketLimiter(RedisGate gate, JudgexProps props) {
        this.gate = gate;
        this.prefix = props.redis() == null ? "jx:" : props.redis().keyPrefix();
    }

    public void acquire(long userId, int perMinute) {
        if (!allowed(userId, perMinute)) {
            throw ApiException.tooMany("too many submissions, limit " + perMinute + "/min");
        }
    }

    private boolean allowed(long userId, int perMinute) {
        if (gate.up()) {
            try {
                Long allowed = gate.template().execute(SCRIPT,
                        List.of(prefix + "rl:submit:" + userId),
                        String.valueOf(perMinute),
                        String.valueOf(perMinute / 60.0),
                        String.valueOf(System.currentTimeMillis()),
                        "120000");
                return allowed != null && allowed == 1L;
            } catch (Exception e) {
                log.debug("redis limiter failed, falling back to local window: {}", e.getMessage());
            }
        }
        return localAllowed(userId, perMinute);
    }

    /** Degraded mode: per-process sliding window. Allowance is not shared, but it still protects the box. */
    private boolean localAllowed(long userId, int perMinute) {
        long now = System.currentTimeMillis();
        long windowStart = now - 60_000;
        Deque<Long> q = localWindows.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() < windowStart) {
                q.pollFirst();
            }
            if (q.size() >= perMinute) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }
}
