package com.judgex.mq;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The transactional outbox: rows are written in the same transaction as the verdict and
 * handed to the broker by {@link OutboxRelay} afterwards.
 */
@Mapper
public interface OutboxMapper {

    record Due(long id, String eventKey, String topic, String payload, int attempts) {
    }

    record Notification(long id, long submissionId, String verdict, String message, String createdAt) {
    }

    record StatusCount(String status, int total) {
    }

    int insertEvent(@Param("eventKey") String eventKey,
                    @Param("topic") String topic,
                    @Param("payload") String payload,
                    @Param("now") LocalDateTime now);

    int reclaim(@Param("now") LocalDateTime now);

    List<Due> selectDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    int claimRow(@Param("id") long id, @Param("leaseUntil") LocalDateTime leaseUntil);

    int markSent(@Param("id") long id, @Param("now") LocalDateTime now);

    int markFailed(@Param("id") long id,
                   @Param("status") String status,
                   @Param("error") String error,
                   @Param("nextAttempt") LocalDateTime nextAttempt);

    List<StatusCount> countByStatus();

    String selectLastError();

    int insertNotification(@Param("submissionId") long submissionId,
                           @Param("userId") long userId,
                           @Param("verdict") String verdict,
                           @Param("message") String message,
                           @Param("now") LocalDateTime now);

    List<Notification> selectNotifications(@Param("userId") Long userId, @Param("limit") int limit);

    /** Must run inside the caller's transaction. Replaying the same event is a no-op. */
    default void append(String eventKey, String topic, String payload) {
        try {
            insertEvent(eventKey, topic, payload, LocalDateTime.now());
        } catch (DuplicateKeyException ignored) {
            // The unique key on event_key makes the append idempotent: a retried judgement
            // must not produce a second event.
        }
    }

    /** Rows leased by a relay that died mid-send go back to PENDING. */
    default int reclaimExpired() {
        return reclaim(LocalDateTime.now());
    }

    default List<Due> due(int limit) {
        return selectDue(LocalDateTime.now(), limit);
    }

    /** CAS claim with a short lease, so two relays (or a restart) cannot double-send. */
    default boolean claim(long id, LocalDateTime leaseUntil) {
        return claimRow(id, leaseUntil) == 1;
    }

    default void sent(long id) {
        markSent(id, LocalDateTime.now());
    }

    default void failed(long id, boolean dead, String error, LocalDateTime nextAttempt) {
        markFailed(id, dead ? "DEAD" : "PENDING", truncate(error, 500), nextAttempt);
    }

    default Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String status : List.of("PENDING", "SENDING", "SENT", "DEAD")) {
            out.put(status.toLowerCase(), 0);
        }
        for (StatusCount row : countByStatus()) {
            out.put(row.status().toLowerCase(), row.total());
        }
        return out;
    }

    default String lastError() {
        return selectLastError();
    }

    default void addNotification(long submissionId, long userId, String verdict, String message) {
        try {
            insertNotification(submissionId, userId, verdict, truncate(message, 250), LocalDateTime.now());
        } catch (DuplicateKeyException ignored) {
            // at-least-once delivery: the consumer side is idempotent on submission_id
        }
    }

    default List<Notification> notifications(Long userId, int limit) {
        return selectNotifications(userId, limit);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
