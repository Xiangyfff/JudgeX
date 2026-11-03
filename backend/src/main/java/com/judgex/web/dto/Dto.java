package com.judgex.web.dto;

import com.judgex.auth.UserPrincipal;

import java.util.List;

/** Wire format for everything the SPA needs. Nested records keep the surface small. */
public final class Dto {

    private Dto() {
    }

    public record ProblemSummary(
            long id, String slug, String title, String difficulty, String tags,
            int timeLimitMs, int memoryLimitMb, boolean published,
            long submissions, long accepted, double acceptRate
    ) {
    }

    public record TestCaseView(
            long id, int ordinal, String input, String expectedOutput, int points, boolean hidden
    ) {
    }

    public record ProblemDetail(
            ProblemSummary summary, String statement, List<TestCaseView> cases
    ) {
    }

    public record SubmissionRow(
            long id, long userId, String user, long problemId, String problemTitle, String problemSlug,
            String language, String status, String verdict, int score, long totalTimeMs,
            int passedCases, int totalCases, String createdAt, String finishedAt
    ) {
    }

    public record TestResultRow(
            int ordinal, String verdict, int timeMs, int memoryKb, String stdout, String stderr, int points
    ) {
    }

    public record SubmissionDetail(
            SubmissionRow row, String sourceCode, String compileOutput, List<TestResultRow> cases
    ) {
    }

    public record CreateSubmissionRequest(Long problemId, String language, String sourceCode) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token, long expiresIn, UserPrincipal user) {
    }

    public record WorkerView(
            String id, String host, int concurrency, int activeTasks, String lastHeartbeat, long heartbeatAgeMs,
            String state
    ) {
    }

    public record QueuePoint(String t, int queued, int running) {
    }

    public record LanguageStatus(String id, String name, String extension, boolean available, String runtime) {
    }

    public record LeaderboardRow(int rank, String name, int solved, long totalMs) {
    }

    public record Dashboard(
            int queued, int running, int capacity, int workersOnline, int activeWorkers,
            long totalSubmissions, long acceptedSubmissions, double acceptRate,
            long avgJudgeMs, long submissionsToday,
            List<QueuePoint> series, List<Dto.SubmissionRow> recent,
            List<WorkerView> workerList, List<LanguageStatus> languages
    ) {
    }

    public record ApiError(String code, String message, String traceId) {
    }

    public record ProblemUpsertRequest(
            String slug, String title, String difficulty, String tags, String statement,
            Integer timeLimitMs, Integer memoryLimitMb, Boolean published
    ) {
    }

    public record TestCaseUpsertRequest(
            Integer ordinal, String input, String expectedOutput, Integer points, Boolean hidden
    ) {
    }

    public record EventEnvelope(String type, long submissionId, Object payload) {
    }

    /** Outbox depth + broker health, surfaced on the operations dashboard. */
    public record MqStats(
            boolean enabled, String broker, String consumer, String topic,
            int pending, int sending, int sent, int dead, String lastError
    ) {
    }

    /** A message the consumer has drained from the topic. */
    public record NotificationRow(long id, long submissionId, String verdict, String message, String createdAt) {
    }
}
