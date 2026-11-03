package com.judgex.mq;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The transactional outbox: rows are written in the same transaction as the verdict and
 * handed to the broker by {@link OutboxRelay} afterwards.
 */
@Repository
public class OutboxRepo {

    public record Due(long id, String eventKey, String topic, String payload, int attempts) {
    }

    public record Notification(long id, long submissionId, String verdict, String message, String createdAt) {
    }

    private final JdbcTemplate jdbc;

    public OutboxRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Must run inside the caller's transaction. Replaying the same event is a no-op. */
    public void append(String eventKey, String topic, String payload) {
        try {
            jdbc.update("""
                    INSERT INTO outbox(event_key, topic, payload, status, attempts, next_attempt_at, created_at)
                    VALUES (?, ?, ?, 'PENDING', 0, ?, ?)
                    """, eventKey, topic, payload, Timestamp.valueOf(LocalDateTime.now()),
                    Timestamp.valueOf(LocalDateTime.now()));
        } catch (DuplicateKeyException ignored) {
            // The unique key on event_key makes the append idempotent: a retried judgement
            // must not produce a second event.
        }
    }

    /** Rows leased by a relay that died mid-send go back to PENDING. */
    public int reclaimExpired() {
        return jdbc.update("UPDATE outbox SET status='PENDING' WHERE status='SENDING' AND next_attempt_at < ?",
                Timestamp.valueOf(LocalDateTime.now()));
    }

    public List<Due> due(int limit) {
        return jdbc.query("""
                SELECT id, event_key, topic, payload, attempts FROM outbox
                 WHERE status='PENDING' AND next_attempt_at <= ?
                 ORDER BY id LIMIT ?
                """, (rs, i) -> new Due(rs.getLong("id"), rs.getString("event_key"),
                rs.getString("topic"), rs.getString("payload"), rs.getInt("attempts")),
                Timestamp.valueOf(LocalDateTime.now()), limit);
    }

    /** CAS claim with a short lease, so two relays (or a restart) cannot double-send. */
    public boolean claim(long id, LocalDateTime leaseUntil) {
        return jdbc.update("""
                UPDATE outbox SET status='SENDING', attempts=attempts+1, next_attempt_at=?
                 WHERE id=? AND status='PENDING'
                """, Timestamp.valueOf(leaseUntil), id) == 1;
    }

    public void sent(long id) {
        jdbc.update("UPDATE outbox SET status='SENT', sent_at=?, last_error=NULL WHERE id=?",
                Timestamp.valueOf(LocalDateTime.now()), id);
    }

    public void failed(long id, boolean dead, String error, LocalDateTime nextAttempt) {
        jdbc.update("UPDATE outbox SET status=?, last_error=?, next_attempt_at=? WHERE id=?",
                dead ? "DEAD" : "PENDING", truncate(error, 500),
                Timestamp.valueOf(nextAttempt), id);
    }

    public Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String status : List.of("PENDING", "SENDING", "SENT", "DEAD")) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE status = ?", Integer.class, status);
            out.put(status.toLowerCase(), n == null ? 0 : n);
        }
        return out;
    }

    public String lastError() {
        List<String> rows = jdbc.queryForList(
                "SELECT last_error FROM outbox WHERE last_error IS NOT NULL ORDER BY id DESC LIMIT 1", String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void addNotification(long submissionId, long userId, String verdict, String message) {
        try {
            jdbc.update("""
                    INSERT INTO notifications(submission_id, user_id, verdict, message, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, submissionId, userId, verdict, truncate(message, 250),
                    Timestamp.valueOf(LocalDateTime.now()));
        } catch (DuplicateKeyException ignored) {
            // at-least-once delivery: the consumer side is idempotent on submission_id
        }
    }

    public List<Notification> notifications(Long userId, int limit) {
        String sql = """
                SELECT id, submission_id, verdict, message, created_at FROM notifications
                """;
        if (userId != null) {
            return jdbc.query(sql + " WHERE user_id = ? ORDER BY id DESC LIMIT ?",
                    (rs, i) -> new Notification(rs.getLong("id"), rs.getLong("submission_id"),
                            rs.getString("verdict"), rs.getString("message"),
                            rs.getTimestamp("created_at").toLocalDateTime().toString()),
                    userId, limit);
        }
        return jdbc.query(sql + " ORDER BY id DESC LIMIT ?",
                (rs, i) -> new Notification(rs.getLong("id"), rs.getLong("submission_id"),
                        rs.getString("verdict"), rs.getString("message"),
                        rs.getTimestamp("created_at").toLocalDateTime().toString()),
                limit);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
