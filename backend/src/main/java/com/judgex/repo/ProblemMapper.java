package com.judgex.repo;

import com.judgex.web.dto.Dto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Problems + their test cases. The default methods keep the Java-side assembly
 * (defaults for optional fields, generated-key handling) that used to live in the
 * old JdbcTemplate repository, so callers did not have to change.
 */
@Mapper
public interface ProblemMapper {

    record JudgeCase(long id, int ordinal, String input, String expectedOutput, int points) {
    }

    List<Dto.ProblemSummary> summaries(@Param("includeUnpublished") boolean includeUnpublished);

    Dto.ProblemSummary selectById(@Param("id") long id);

    Dto.ProblemSummary selectBySlug(@Param("slug") String slug);

    String selectStatement(@Param("id") long id);

    List<Dto.TestCaseView> cases(@Param("problemId") long problemId, @Param("includeHidden") boolean includeHidden);

    List<JudgeCase> judgeCases(@Param("problemId") long problemId);

    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertRow(Map<String, Object> row);

    int updateRow(@Param("id") long id,
                  @Param("slug") String slug,
                  @Param("title") String title,
                  @Param("difficulty") String difficulty,
                  @Param("tags") String tags,
                  @Param("statement") String statement,
                  @Param("timeLimitMs") int timeLimitMs,
                  @Param("memoryLimitMb") int memoryLimitMb,
                  @Param("published") boolean published);

    int deleteCases(@Param("problemId") long problemId);

    int deleteSubmissions(@Param("problemId") long problemId);

    int deleteById(@Param("id") long id);

    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertCaseRow(Map<String, Object> row);

    Integer maxOrdinal(@Param("problemId") long problemId);

    long count();

    default Optional<Dto.ProblemSummary> find(long id) {
        return Optional.ofNullable(selectById(id));
    }

    default Optional<Dto.ProblemSummary> findBySlug(String slug) {
        return Optional.ofNullable(selectBySlug(slug));
    }

    default String statement(long id) {
        String text = selectStatement(id);
        return text == null ? "" : text;
    }

    default long insert(Dto.ProblemUpsertRequest r) {
        Map<String, Object> row = new HashMap<>();
        row.put("slug", r.slug());
        row.put("title", r.title());
        row.put("difficulty", r.difficulty());
        row.put("tags", r.tags() == null ? "" : r.tags());
        row.put("statement", r.statement());
        row.put("timeLimitMs", r.timeLimitMs() == null ? 2000 : r.timeLimitMs());
        row.put("memoryLimitMb", r.memoryLimitMb() == null ? 256 : r.memoryLimitMb());
        row.put("published", r.published() == null || r.published());
        insertRow(row);
        Object id = row.get("id");
        return id instanceof Number n ? n.longValue() : -1;
    }

    default void update(long id, Dto.ProblemUpsertRequest r) {
        updateRow(id, r.slug(), r.title(), r.difficulty(), r.tags() == null ? "" : r.tags(),
                r.statement(),
                r.timeLimitMs() == null ? 2000 : r.timeLimitMs(),
                r.memoryLimitMb() == null ? 256 : r.memoryLimitMb(),
                r.published() == null || r.published());
    }

    default void delete(long id) {
        deleteCases(id);
        deleteSubmissions(id);
        deleteById(id);
    }

    default void replaceCases(long problemId, List<Dto.TestCaseUpsertRequest> cases) {
        deleteCases(problemId);
        int ordinal = 1;
        for (Dto.TestCaseUpsertRequest c : cases) {
            insertCaseRow(caseRow(problemId, c.ordinal() == null ? ordinal : c.ordinal(), c));
            ordinal++;
        }
    }

    default void insertCase(long problemId, Dto.TestCaseUpsertRequest c) {
        Integer max = maxOrdinal(problemId);
        int next = (max == null ? 0 : max) + 1;
        insertCaseRow(caseRow(problemId, c.ordinal() == null ? next : c.ordinal(), c));
    }

    private Map<String, Object> caseRow(long problemId, int ordinal, Dto.TestCaseUpsertRequest c) {
        Map<String, Object> row = new HashMap<>();
        row.put("problemId", problemId);
        row.put("ordinal", ordinal);
        row.put("input", c.input() == null ? "" : c.input());
        row.put("expectedOutput", c.expectedOutput() == null ? "" : c.expectedOutput());
        row.put("points", c.points() == null ? 10 : c.points());
        row.put("hidden", c.hidden() == null || c.hidden());
        return row;
    }
}
