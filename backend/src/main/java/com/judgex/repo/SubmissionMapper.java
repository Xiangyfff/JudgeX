package com.judgex.repo;

import com.judgex.web.dto.Dto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Mapper
public interface SubmissionMapper {

    /** Per problem take the fastest accepted run, then sum — the ranking's time axis. */
    record Best(int solved, long totalMs) {
    }

    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertRow(Map<String, Object> row);

    Long selectIdByIdem(@Param("userId") long userId, @Param("idemKey") String idemKey);

    Dto.SubmissionRow selectById(@Param("id") long id);

    List<Dto.SubmissionRow> selectList(@Param("userId") Long userId,
                                       @Param("problemId") Long problemId,
                                       @Param("limit") int limit);

    List<Dto.SubmissionRow> selectRecent(@Param("limit") int limit);

    String selectSource(@Param("id") long id);

    String selectCompileOutput(@Param("id") long id);

    List<Dto.TestResultRow> results(@Param("submissionId") long submissionId);

    int markRunningRow(@Param("id") long id, @Param("now") LocalDateTime now);

    int finishRow(@Param("id") long id,
                  @Param("status") String status,
                  @Param("verdict") String verdict,
                  @Param("score") int score,
                  @Param("compileOutput") String compileOutput,
                  @Param("totalTimeMs") long totalTimeMs,
                  @Param("now") LocalDateTime now);

    int deleteResults(@Param("submissionId") long submissionId);

    int resetRow(@Param("id") long id);

    int insertResult(@Param("submissionId") long submissionId,
                     @Param("caseId") long caseId,
                     @Param("ordinal") int ordinal,
                     @Param("verdict") String verdict,
                     @Param("timeMs") int timeMs,
                     @Param("memoryKb") int memoryKb,
                     @Param("stdout") String stdout,
                     @Param("stderr") String stderr,
                     @Param("points") int points);

    int activeCount(@Param("userId") long userId);

    String statusOf(@Param("id") long id);

    long countAll();

    Best bestAccepted(@Param("userId") long userId);

    default long insert(long userId, long problemId, String language, String source, String idemKey) {
        Map<String, Object> row = new HashMap<>();
        row.put("userId", userId);
        row.put("problemId", problemId);
        row.put("language", language);
        row.put("source", source);
        row.put("idemKey", idemKey);
        insertRow(row);
        Object id = row.get("id");
        return id instanceof Number n ? n.longValue() : -1;
    }

    default Optional<Long> findIdByIdem(long userId, String idemKey) {
        return Optional.ofNullable(selectIdByIdem(userId, idemKey));
    }

    default Optional<Dto.SubmissionRow> find(long id) {
        return Optional.ofNullable(selectById(id));
    }

    default List<Dto.SubmissionRow> list(Long userId, Long problemId, int limit) {
        return selectList(userId, problemId, limit);
    }

    default List<Dto.SubmissionRow> recent(int limit) {
        return selectRecent(limit);
    }

    default String source(long id) {
        String text = selectSource(id);
        return text == null ? "" : text;
    }

    default String compileOutput(long id) {
        return selectCompileOutput(id);
    }

    default void markRunning(long id) {
        markRunningRow(id, LocalDateTime.now());
    }

    default void finish(long id, String status, String verdict, int score, String compileOutput, long totalTimeMs) {
        finishRow(id, status, verdict, score, compileOutput, totalTimeMs, LocalDateTime.now());
    }

    /** Re-queueing a task must not leave the half-written results of the previous attempt behind. */
    default void resetForRetry(long id) {
        deleteResults(id);
        resetRow(id);
    }

    default void addResult(long submissionId, long caseId, int ordinal, String verdict,
                           int timeMs, int memoryKb, String stdout, String stderr, int points) {
        insertResult(submissionId, caseId, ordinal, verdict, timeMs, memoryKb,
                truncate(stdout), truncate(stderr), points);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 8000 ? s.substring(0, 8000) + "\n…(truncated)" : s;
    }
}
