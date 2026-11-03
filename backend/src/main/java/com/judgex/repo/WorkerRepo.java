package com.judgex.repo;

import com.judgex.web.dto.Dto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class WorkerRepo {

    private final JdbcTemplate jdbc;

    public WorkerRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Upsert heartbeat; the workers table doubles as the discovery + health view. */
    public void heartbeat(String id, String host, int concurrency, int activeTasks) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                MERGE INTO workers (id, host, concurrency, active_tasks, last_heartbeat, started_at)
                KEY (id) VALUES (?,?,?,?,?, COALESCE((SELECT started_at FROM workers WHERE id = ?), ?))
                """, id, host, concurrency, activeTasks, Timestamp.valueOf(now), id, Timestamp.valueOf(now));
    }

    public List<Dto.WorkerView> list(long staleAfterMs) {
        LocalDateTime now = LocalDateTime.now();
        return jdbc.query("SELECT id, host, concurrency, active_tasks, last_heartbeat FROM workers ORDER BY id",
                (rs, i) -> {
                    Timestamp hb = rs.getTimestamp("last_heartbeat");
                    long age = hb == null ? Long.MAX_VALUE
                            : Duration.between(hb.toLocalDateTime(), now).toMillis();
                    return new Dto.WorkerView(
                            rs.getString("id"), rs.getString("host"), rs.getInt("concurrency"),
                            rs.getInt("active_tasks"), hb == null ? null : hb.toLocalDateTime().toString(),
                            age, age > staleAfterMs ? "STALE" : "ONLINE");
                });
    }

    public int capacity() {
        Integer n = jdbc.queryForObject("SELECT COALESCE(SUM(concurrency),0) FROM workers WHERE last_heartbeat > ?",
                Integer.class, Timestamp.valueOf(LocalDateTime.now().minusSeconds(15)));
        return n == null ? 0 : n;
    }

    public int onlineCount(long staleAfterMs) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM workers WHERE last_heartbeat > ?",
                Integer.class, Timestamp.valueOf(LocalDateTime.now().minusSeconds(staleAfterMs / 1000)));
        return n == null ? 0 : n;
    }

    public void prune(long staleAfterMs) {
        jdbc.update("DELETE FROM workers WHERE last_heartbeat < ?",
                Timestamp.valueOf(LocalDateTime.now().minusSeconds(staleAfterMs / 1000)));
    }
}
