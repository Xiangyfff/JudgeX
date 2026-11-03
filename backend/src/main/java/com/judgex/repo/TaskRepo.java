package com.judgex.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The queue itself. Deliberately SQL-only: a judge queue that survives a JVM
 * restart without an external broker, and whose claim is a single atomic UPDATE.
 */
@Repository
public class TaskRepo {

    public record ClaimedTask(long id, long submissionId, int attempts) {
    }

    public record Lease(long taskId, long submissionId, int attempts) {
    }

    private final JdbcTemplate jdbc;

    public TaskRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(long submissionId) {
        jdbc.update("INSERT INTO judge_tasks (submission_id, status) VALUES (?, 'QUEUED')", submissionId);
    }

    public List<Long> candidates(int limit) {
        return jdbc.queryForList(
                "SELECT id FROM judge_tasks WHERE status = 'QUEUED' ORDER BY id LIMIT ?", Long.class, limit);
    }

    /**
     * Compare-and-set claim. Two workers racing for the same row: exactly one
     * update affects a row, the other sees 0 and moves on. No locks held.
     */
    public boolean tryClaim(long taskId, String workerId, long leaseSeconds) {
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbc.update("""
                UPDATE judge_tasks
                   SET status = 'ASSIGNED', worker_id = ?, attempts = attempts + 1,
                       lease_expires_at = ?, updated_at = ?
                 WHERE id = ? AND status = 'QUEUED'
                """, workerId, Timestamp.valueOf(now.plusSeconds(leaseSeconds)),
                Timestamp.valueOf(now), taskId);
        return updated == 1;
    }

    public ClaimedTask claimed(long taskId) {
        return jdbc.queryForObject(
                "SELECT id, submission_id, attempts FROM judge_tasks WHERE id = ?",
                (rs, i) -> new ClaimedTask(rs.getLong(1), rs.getLong(2), rs.getInt(3)), taskId);
    }

    public List<Lease> expiredLeases(int limit) {
        return jdbc.query("""
                SELECT id, submission_id, attempts FROM judge_tasks
                 WHERE status = 'ASSIGNED' AND lease_expires_at < ?
                 ORDER BY id LIMIT ?
                """, (rs, i) -> new Lease(rs.getLong(1), rs.getLong(2), rs.getInt(3)),
                Timestamp.valueOf(LocalDateTime.now()), limit);
    }

    public boolean requeue(long taskId) {
        int updated = jdbc.update("""
                UPDATE judge_tasks
                   SET status = 'QUEUED', worker_id = NULL, lease_expires_at = NULL,
                       last_error = 'lease expired', updated_at = ?
                 WHERE id = ? AND status = 'ASSIGNED'
                """, Timestamp.valueOf(LocalDateTime.now()), taskId);
        return updated == 1;
    }

    /**
     * Lease renewal. Without this a long-running judge (many test cases) would
     * look dead to the reclaimer and get executed twice.
     */
    public int renewLeases(String workerId, long leaseSeconds) {
        return jdbc.update("""
                UPDATE judge_tasks SET lease_expires_at = ?, updated_at = ?
                 WHERE worker_id = ? AND status = 'ASSIGNED'
                """, Timestamp.valueOf(LocalDateTime.now().plusSeconds(leaseSeconds)),
                Timestamp.valueOf(LocalDateTime.now()), workerId);
    }

    public void complete(long taskId) {
        jdbc.update("UPDATE judge_tasks SET status='DONE', lease_expires_at=NULL, updated_at=? WHERE id=?",
                Timestamp.valueOf(LocalDateTime.now()), taskId);
    }

    public void deadLetter(long taskId, String error) {
        jdbc.update("""
                UPDATE judge_tasks SET status='DEAD', lease_expires_at=NULL, last_error=?, updated_at=?
                WHERE id=?
                """, error == null ? "unknown" : truncate(error), Timestamp.valueOf(LocalDateTime.now()), taskId);
    }

    private String truncate(String s) {
        return s.length() > 480 ? s.substring(0, 480) : s;
    }

    public long submissionIdOf(long taskId) {
        Long v = jdbc.queryForObject("SELECT submission_id FROM judge_tasks WHERE id = ?", Long.class, taskId);
        return v == null ? -1 : v;
    }

    public int queueDepth() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM judge_tasks WHERE status = 'QUEUED'", Integer.class);
        return n == null ? 0 : n;
    }

    public int running() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM judge_tasks WHERE status = 'ASSIGNED'", Integer.class);
        return n == null ? 0 : n;
    }

    public int deadCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM judge_tasks WHERE status = 'DEAD'", Integer.class);
        return n == null ? 0 : n;
    }
}
