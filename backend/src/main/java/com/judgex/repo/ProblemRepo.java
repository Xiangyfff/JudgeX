package com.judgex.repo;

import com.judgex.web.dto.Dto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class ProblemRepo {

    public record JudgeCase(long id, int ordinal, String input, String expectedOutput, int points) {
    }

    private static final String SUMMARY_SELECT = """
            SELECT p.id, p.slug, p.title, p.difficulty, p.tags, p.time_limit_ms, p.memory_limit_mb, p.published,
                   COALESCE(SUM(CASE WHEN s.id IS NULL THEN 0 ELSE 1 END), 0) AS subs,
                   COALESCE(SUM(CASE WHEN s.verdict = 'AC' THEN 1 ELSE 0 END), 0) AS acs
            FROM problems p
            LEFT JOIN submissions s ON s.problem_id = p.id
            """;

    private final JdbcTemplate jdbc;

    public ProblemRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Dto.ProblemSummary> summaries(boolean includeUnpublished) {
        String sql = SUMMARY_SELECT
                + (includeUnpublished ? "" : " WHERE p.published = TRUE")
                + " GROUP BY p.id, p.slug, p.title, p.difficulty, p.tags, p.time_limit_ms, p.memory_limit_mb, p.published"
                + " ORDER BY p.id";
        return jdbc.query(sql, this::mapSummary);
    }

    public Optional<Dto.ProblemSummary> find(long id) {
        List<Dto.ProblemSummary> rows = jdbc.query(
                SUMMARY_SELECT + " WHERE p.id = ? GROUP BY p.id, p.slug, p.title, p.difficulty, p.tags, p.time_limit_ms, p.memory_limit_mb, p.published",
                this::mapSummary, id);
        return rows.stream().findFirst();
    }

    public Optional<Dto.ProblemSummary> findBySlug(String slug) {
        List<Dto.ProblemSummary> rows = jdbc.query(
                SUMMARY_SELECT + " WHERE p.slug = ? GROUP BY p.id, p.slug, p.title, p.difficulty, p.tags, p.time_limit_ms, p.memory_limit_mb, p.published",
                this::mapSummary, slug);
        return rows.stream().findFirst();
    }

    private Dto.ProblemSummary mapSummary(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        long subs = rs.getLong("subs");
        long acs = rs.getLong("acs");
        return new Dto.ProblemSummary(
                rs.getLong("id"), rs.getString("slug"), rs.getString("title"),
                rs.getString("difficulty"), rs.getString("tags"),
                rs.getInt("time_limit_ms"), rs.getInt("memory_limit_mb"), rs.getBoolean("published"),
                subs, acs, subs == 0 ? 0.0 : Math.round(acs * 1000.0 / subs) / 10.0);
    }

    public String statement(long id) {
        List<String> rows = jdbc.queryForList("SELECT statement FROM problems WHERE id = ?", String.class, id);
        return rows.isEmpty() ? "" : rows.get(0);
    }

    public List<Dto.TestCaseView> cases(long problemId, boolean includeHidden) {
        String sql = "SELECT id, ordinal, input, expected_output, points, hidden FROM test_cases WHERE problem_id = ?"
                + (includeHidden ? "" : " AND hidden = FALSE") + " ORDER BY ordinal";
        return jdbc.query(sql, (rs, i) -> new Dto.TestCaseView(
                rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getBoolean(6)),
                problemId);
    }

    public List<JudgeCase> judgeCases(long problemId) {
        return jdbc.query(
                "SELECT id, ordinal, input, expected_output, points FROM test_cases WHERE problem_id = ? ORDER BY ordinal",
                (rs, i) -> new JudgeCase(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getInt(5)),
                problemId);
    }

    public long insert(Dto.ProblemUpsertRequest r) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO problems (slug, title, difficulty, tags, statement, time_limit_ms, memory_limit_mb, published)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, new String[]{"id"});
            ps.setString(1, r.slug());
            ps.setString(2, r.title());
            ps.setString(3, r.difficulty());
            ps.setString(4, r.tags() == null ? "" : r.tags());
            ps.setString(5, r.statement());
            ps.setInt(6, r.timeLimitMs() == null ? 2000 : r.timeLimitMs());
            ps.setInt(7, r.memoryLimitMb() == null ? 256 : r.memoryLimitMb());
            ps.setBoolean(8, r.published() == null || r.published());
            return ps;
        }, kh);
        return kh.getKey() == null ? -1 : kh.getKey().longValue();
    }

    public void update(long id, Dto.ProblemUpsertRequest r) {
        jdbc.update("""
                UPDATE problems SET slug=?, title=?, difficulty=?, tags=?, statement=?,
                       time_limit_ms=?, memory_limit_mb=?, published=?
                WHERE id=?
                """,
                r.slug(), r.title(), r.difficulty(), r.tags() == null ? "" : r.tags(), r.statement(),
                r.timeLimitMs() == null ? 2000 : r.timeLimitMs(),
                r.memoryLimitMb() == null ? 256 : r.memoryLimitMb(),
                r.published() == null || r.published(), id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM test_cases WHERE problem_id = ?", id);
        jdbc.update("DELETE FROM submissions WHERE problem_id = ?", id);
        jdbc.update("DELETE FROM problems WHERE id = ?", id);
    }

    public void replaceCases(long problemId, List<Dto.TestCaseUpsertRequest> cases) {
        jdbc.update("DELETE FROM test_cases WHERE problem_id = ?", problemId);
        List<Object[]> batch = new ArrayList<>();
        int ordinal = 1;
        for (Dto.TestCaseUpsertRequest c : cases) {
            batch.add(new Object[]{problemId, c.ordinal() == null ? ordinal : c.ordinal(),
                    c.input() == null ? "" : c.input(),
                    c.expectedOutput() == null ? "" : c.expectedOutput(),
                    c.points() == null ? 10 : c.points(),
                    c.hidden() == null || c.hidden()});
            ordinal++;
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO test_cases (problem_id, ordinal, input, expected_output, points, hidden) VALUES (?,?,?,?,?,?)", batch);
        }
    }

    public void insertCase(long problemId, Dto.TestCaseUpsertRequest c) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(ordinal),0) FROM test_cases WHERE problem_id = ?",
                Integer.class, problemId);
        int next = (max == null ? 0 : max) + 1;
        jdbc.update("INSERT INTO test_cases (problem_id, ordinal, input, expected_output, points, hidden) VALUES (?,?,?,?,?,?)",
                problemId, c.ordinal() == null ? next : c.ordinal(),
                c.input() == null ? "" : c.input(),
                c.expectedOutput() == null ? "" : c.expectedOutput(),
                c.points() == null ? 10 : c.points(),
                c.hidden() == null || c.hidden());
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM problems", Long.class);
        return n == null ? 0 : n;
    }
}
