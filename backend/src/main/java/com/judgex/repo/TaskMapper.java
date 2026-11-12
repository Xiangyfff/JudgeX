package com.judgex.repo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The queue itself. Deliberately SQL-only: a judge queue that survives a JVM
 * restart without an external broker, and whose claim is a single atomic UPDATE.
 */
@Mapper
public interface TaskMapper {

    record ClaimedTask(long id, long submissionId, int attempts) {
    }

    record Lease(long taskId, long submissionId, int attempts) {
    }

    int insertEnqueue(@Param("submissionId") long submissionId);

    List<Long> selectCandidates(@Param("limit") int limit);

    int claim(@Param("taskId") long taskId,
              @Param("workerId") String workerId,
              @Param("leaseUntil") LocalDateTime leaseUntil,
              @Param("now") LocalDateTime now);

    ClaimedTask selectClaimed(@Param("taskId") long taskId);

    List<Lease> selectExpiredLeases(@Param("now") LocalDateTime now, @Param("limit") int limit);

    int requeueRow(@Param("taskId") long taskId, @Param("now") LocalDateTime now);

    int renewLeases(@Param("workerId") String workerId,
                    @Param("leaseUntil") LocalDateTime leaseUntil,
                    @Param("now") LocalDateTime now);

    int complete(@Param("taskId") long taskId, @Param("now") LocalDateTime now);

    int deadLetter(@Param("taskId") long taskId, @Param("error") String error, @Param("now") LocalDateTime now);

    Long selectSubmissionId(@Param("taskId") long taskId);

    int countQueued();

    int countAssigned();

    int countDead();

    default void enqueue(long submissionId) {
        insertEnqueue(submissionId);
    }

    default List<Long> candidates(int limit) {
        return selectCandidates(limit);
    }

    /**
     * Compare-and-set claim. Two workers racing for the same row: exactly one
     * update affects a row, the other sees 0 and moves on. No locks held.
     */
    default boolean tryClaim(long taskId, String workerId, long leaseSeconds) {
        LocalDateTime now = LocalDateTime.now();
        return claim(taskId, workerId, now.plusSeconds(leaseSeconds), now) == 1;
    }

    default ClaimedTask claimed(long taskId) {
        return selectClaimed(taskId);
    }

    default List<Lease> expiredLeases(int limit) {
        return selectExpiredLeases(LocalDateTime.now(), limit);
    }

    default boolean requeue(long taskId) {
        return requeueRow(taskId, LocalDateTime.now()) == 1;
    }

    /**
     * Lease renewal. Without this a long-running judge (many test cases) would
     * look dead to the reclaimer and get executed twice.
     */
    default int renewLeases(String workerId, long leaseSeconds) {
        LocalDateTime now = LocalDateTime.now();
        return renewLeases(workerId, now.plusSeconds(leaseSeconds), now);
    }

    default void complete(long taskId) {
        complete(taskId, LocalDateTime.now());
    }

    default void deadLetter(long taskId, String error) {
        deadLetter(taskId, error == null ? "unknown" : truncate(error), LocalDateTime.now());
    }

    default long submissionIdOf(long taskId) {
        Long v = selectSubmissionId(taskId);
        return v == null ? -1 : v;
    }

    default int queueDepth() {
        return countQueued();
    }

    default int running() {
        return countAssigned();
    }

    default int deadCount() {
        return countDead();
    }

    private static String truncate(String s) {
        return s.length() > 480 ? s.substring(0, 480) : s;
    }
}
