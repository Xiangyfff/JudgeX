package com.judgex.repo;

import com.judgex.web.dto.Dto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class SubmissionRepo {

    private static final String ROW_SELECT = """
            SELECT s.id, s.user_id, u.display_name AS user_name, s.problem_id, p.title AS problem_title,
                   p.slug AS problem_slug, s.language, s.status, s.verdict, s.score, s.total_time_ms,
                   (SELECT COUNT(*) FROM test_results r WHERE r.submission_id = s.id) AS total_cases,
                   (SELECT COUNT(*) FROM test_results r WHERE r.submission_id = s.id AND r.verdict = 'AC') AS passed_cases,
                   s.created_at, s.finished_at
            FROM submissions s
            JOIN users u ON u.id = s.user_id
            JOIN problems p ON p.id = s.problem_id
            """;

    private final JdbcTemplate jdbc;

    public SubmissionRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long userId, long problemId, String language, String source, String idemKey) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO submissions (user_id, problem_id, language, source_code, status, idem_key)
                    VALUES (?,?,?,?,?,?)
                    """, new String[]{"id"});
            ps.setLong(1, userId);
            ps.setLong(2, problemId);
            ps.setString(3, language);
            ps.setString(4, source);
            ps.setString(5, "QUEUED");
            ps.setString(6, idemKey);
            return ps;
        }, kh);
        return kh.getKey() == null ? -1 : kh.getKey().longValue();
    }

    public Optional<Long> findIdByIdem(long userId, String idemKey) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM submissions WHERE user_id = ? AND idem_key = ?", Long.class, userId, idemKey);
        return ids.stream().findFirst();
    }

    public Optional<Dto.SubmissionRow> find(long id) {
        List<Dto.SubmissionRow> rows = jdbc.query(ROW_SELECT + " WHERE s.id = ?", this::mapRow, id);
        return rows.stream().findFirst();
    }

    public List<Dto.SubmissionRow> list(Long userId, Long problemId, int limit) {
        StringBuilder sql = new StringBuilder(ROW_SELECT).append(" WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (userId != null) {
            sql.append(" AND s.user_id = ?");
            args.add(userId);
        }
        if (problemId != null) {
            sql.append(" AND s.problem_id = ?");
            args.add(problemId);
        }
        sql.append(" ORDER BY s.id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), this::mapRow, args.toArray());
    }

    public List<Dto.SubmissionRow> recent(int limit) {
        return jdbc.query(ROW_SELECT + " ORDER BY s.id DESC LIMIT ?", this::mapRow, limit);
    }

    private Dto.SubmissionRow mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp finished = rs.getTimestamp("finished_at");
        return new Dto.SubmissionRow(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("user_name"),
                rs.getLong("problem_id"), rs.getString("problem_title"), rs.getString("problem_slug"),
                rs.getString("language"), rs.getString("status"), rs.getString("verdict"),
                rs.getInt("score"), rs.getLong("total_time_ms"),
                rs.getInt("passed_cases"), rs.getInt("total_cases"),
                created == null ? null : created.toLocalDateTime().toString(),
                finished == null ? null : finished.toLocalDateTime().toString());
    }

    public String source(long id) {
        List<String> rows = jdbc.queryForList("SELECT source_code FROM submissions WHERE id = ?", String.class, id);
        return rows.isEmpty() ? "" : rows.get(0);
    }

    public String compileOutput(long id) {
        List<String> rows = jdbc.queryForList("SELECT compile_output FROM submissions WHERE id = ?", String.class, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Dto.TestResultRow> results(long submissionId) {
        return jdbc.query("""
                SELECT ordinal, verdict, time_ms, memory_kb, stdout, stderr, points
                FROM test_results WHERE submission_id = ? ORDER BY ordinal
                """, (rs, i) -> new Dto.TestResultRow(
                rs.getInt(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                rs.getString(5), rs.getString(6), rs.getInt(7)), submissionId);
    }

    public void markRunning(long id) {
        jdbc.update("UPDATE submissions SET status='RUNNING', started_at=? WHERE id=?",
                Timestamp.valueOf(LocalDateTime.now()), id);
    }

    public void finish(long id, String status, String verdict, int score, String compileOutput, long totalTimeMs) {
        jdbc.update("""
                UPDATE submissions SET status=?, verdict=?, score=?, compile_output=?, total_time_ms=?, finished_at=?
                WHERE id=?
                """, status, verdict, score, compileOutput, totalTimeMs,
                Timestamp.valueOf(LocalDateTime.now()), id);
    }

    public void resetForRetry(long id) {
        jdbc.update("DELETE FROM test_results WHERE submission_id = ?", id);
        jdbc.update("UPDATE submissions SET status='QUEUED', verdict=NULL, started_at=NULL WHERE id=?", id);
    }

    public void addResult(long submissionId, long caseId, int ordinal, String verdict,
                          int timeMs, int memoryKb, String stdout, String stderr, int points) {
        jdbc.update("""
                INSERT INTO test_results (submission_id, case_id, ordinal, verdict, time_ms, memory_kb, stdout, stderr, points)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, submissionId, caseId, ordinal, verdict, timeMs, memoryKb,
                truncate(stdout), truncate(stderr), points);
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 8000 ? s.substring(0, 8000) + "\n…(truncated)" : s;
    }

    public int activeCount(long userId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM submissions
                WHERE user_id = ? AND status IN ('PENDING','QUEUED','RUNNING')
                """, Integer.class, userId);
        return n == null ? 0 : n;
    }

    public String statusOf(long id) {
        List<String> rows = jdbc.queryForList("SELECT status FROM submissions WHERE id = ?", String.class, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long countAll() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM submissions", Long.class);
        return n == null ? 0 : n;
    }

    /** Per problem take the fastest accepted run, then sum — the ranking's time axis. */
    public record Best(int solved, long totalMs) {
    }

    public Best bestAccepted(long userId) {
        Best best = jdbc.query("""
                SELECT COUNT(*) AS solved, COALESCE(SUM(best), 0) AS total FROM (
                    SELECT MIN(total_time_ms) AS best
                    FROM submissions
                    WHERE user_id = ? AND verdict = 'AC'
                    GROUP BY problem_id) t
                """, rs -> rs.next() ? new Best(rs.getInt("solved"), rs.getLong("total")) : new Best(0, 0),
                userId);
        return best == null ? new Best(0, 0) : best;
    }
}
