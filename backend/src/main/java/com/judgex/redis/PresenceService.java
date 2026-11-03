package com.judgex.redis;

import com.judgex.config.JudgexProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Worker liveness as a TTL key instead of a heartbeat column.
 * <p>
 * The database row still exists (it is the durable record), but the fast question — "which
 * nodes are alive right now" — is answered by a key that expires on its own. A crashed worker
 * simply stops refreshing and disappears; there is no sweep to run and no clock skew to argue
 * about.
 */
@Component
public class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);
    private static final Duration TTL = Duration.ofSeconds(10);

    private final RedisGate gate;
    private final String prefix;

    public PresenceService(RedisGate gate, JudgexProps props) {
        this.gate = gate;
        this.prefix = props.redis() == null ? "jx:" : props.redis().keyPrefix();
    }

    public void beat(String workerId, String host, int capacity, int active) {
        if (!gate.up()) {
            return;
        }
        try {
            String payload = host + "|" + capacity + "|" + active + "|" + System.currentTimeMillis();
            gate.template().opsForValue().set(prefix + "worker:" + workerId, payload, TTL);
        } catch (Exception e) {
            log.debug("presence beat failed: {}", e.getMessage());
        }
    }

    /** Node ids currently holding a live key. Small N, so SCAN is enough. */
    public List<String> online() {
        if (!gate.up()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        try (Cursor<String> cursor = gate.template().scan(
                ScanOptions.scanOptions().match(prefix + "worker:*").count(100).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                ids.add(key.substring((prefix + "worker:").length()));
            }
        } catch (Exception e) {
            log.debug("presence scan failed: {}", e.getMessage());
        }
        return ids;
    }
}
