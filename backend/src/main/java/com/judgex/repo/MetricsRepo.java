package com.judgex.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

@Repository
public class MetricsRepo {

    private final JdbcTemplate jdbc;

    public MetricsRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long submissionsToday() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM submissions WHERE created_at >= ?",
                Long.class, Timestamp.valueOf(LocalDateTime.now().toLocalDate().atStartOfDay()));
        return n == null ? 0 : n;
    }

    public long totalSubmissions() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM submissions", Long.class);
        return n == null ? 0 : n;
    }

    public long acceptedSubmissions() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM submissions WHERE verdict = 'AC'", Long.class);
        return n == null ? 0 : n;
    }

    /** Average judge latency over the last 200 finished runs — steady state, not all-time. */
    public long avgJudgeMs() {
        Long v = jdbc.queryForObject("""
                SELECT CAST(COALESCE(AVG(total_time_ms), 0) AS BIGINT) FROM (
                    SELECT total_time_ms FROM submissions
                     WHERE finished_at IS NOT NULL
                     ORDER BY id DESC LIMIT 200
                ) t
                """, Long.class);
        return v == null ? 0 : v;
    }
}
