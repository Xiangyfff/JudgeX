package com.judgex.service;

import com.judgex.config.JudgexProps;
import com.judgex.repo.ProblemMapper;
import com.judgex.repo.SubmissionMapper;
import com.judgex.redis.LeaderboardService;
import com.judgex.sandbox.ExecResult;
import com.judgex.sandbox.LanguageRegistry;
import com.judgex.sandbox.SandboxRunner;
import com.judgex.web.dto.Dto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Hermetic tests for the early exits of JudgeEngine.judge(): unsupported language,
 * compile infrastructure failure, compile error (CE) and replay suppression.
 * All collaborators are Mockito mocks; no Spring, database or sandbox process is started.
 */
class JudgeEngineJudgeTest {

    private static final long SUBMISSION_ID = 42L;

    private final SubmissionMapper submissions = mock(SubmissionMapper.class);
    private final ProblemMapper problems = mock(ProblemMapper.class);
    private final LanguageRegistry languages = mock(LanguageRegistry.class);
    private final SandboxRunner sandbox = mock(SandboxRunner.class);
    private final ProgressHub hub = mock(ProgressHub.class);
    private final LeaderboardService leaderboard = mock(LeaderboardService.class);
    private final ResultService results = mock(ResultService.class);

    @TempDir
    Path workDir;

    private JudgeEngine engine;
    private Dto.SubmissionRow row;

    @BeforeEach
    void setUp() {
        row = new Dto.SubmissionRow(SUBMISSION_ID, 7L, "alice", 3L, "Two Sum", "two-sum",
                "java", "QUEUED", null, 0, 0, 0, 0, "2026-01-01 00:00:00", null);
        JudgexProps props = new JudgexProps("test-secret", 1, null,
                new JudgexProps.Sandbox("local", 4096, workDir.toString(), "", "", "", 0, 0),
                null, null, null);
        engine = new JudgeEngine(submissions, problems, languages, sandbox, hub, props,
                leaderboard, results);

        when(submissions.find(SUBMISSION_ID)).thenReturn(Optional.of(row));
        when(submissions.markRunning(SUBMISSION_ID)).thenReturn(1);
        when(submissions.results(SUBMISSION_ID)).thenReturn(List.of());
    }

    private static LanguageRegistry.Language javaLanguage() {
        return new LanguageRegistry.Language("java", "Java", "Main.java", "java",
                List.of("javac", "Main.java"), List.of("java", "Main"),
                null, null, "-Xmx%dm");
    }

    @Test
    @DisplayName("编译退出码非 0 -> CE，编译日志与编译耗时落库")
    void compileFailureProducesCe() {
        when(languages.get("java")).thenReturn(javaLanguage());
        when(submissions.source(SUBMISSION_ID)).thenReturn("class Main {}");
        when(sandbox.exec(anyList(), any(Path.class), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new ExecResult(1, "", "Main.java:1: error: ';' expected",
                        12, 12, 0, false, false, null));
        when(results.finishAndPublish(row, "CE", 0, "Main.java:1: error: ';' expected", 12L))
                .thenReturn(true);

        engine.judge(SUBMISSION_ID);

        verify(results).finishAndPublish(row, "CE", 0, "Main.java:1: error: ';' expected", 12L);
        verify(hub).finish(eq(SUBMISSION_ID), anyMap());
        verify(submissions, never()).addResult(anyLong(), anyLong(), anyInt(), anyString(),
                anyInt(), anyInt(), anyString(), anyString(), anyInt());
        verify(problems, never()).judgeCases(anyLong());
    }

    @Test
    @DisplayName("编译命令根本无法执行（error 非空）-> SYSTEM_ERROR")
    void compileInfrastructureFailureProducesSystemError() {
        when(languages.get("java")).thenReturn(javaLanguage());
        when(submissions.source(SUBMISSION_ID)).thenReturn("class Main {}");
        when(sandbox.exec(anyList(), any(Path.class), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new ExecResult(-1, "", "", 0, -1, 0, false, false, "docker daemon down"));
        when(results.finishAndPublish(row, "SYSTEM_ERROR", 0, "", 0L)).thenReturn(true);

        engine.judge(SUBMISSION_ID);

        verify(results).finishAndPublish(row, "SYSTEM_ERROR", 0, "", 0L);
        verify(submissions, never()).addResult(anyLong(), anyLong(), anyInt(), anyString(),
                anyInt(), anyInt(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("节点不支持该语言 -> SYSTEM_ERROR，且不创建工目录、不跑沙箱")
    void unsupportedLanguageProducesSystemError() {
        when(languages.get("java")).thenReturn(null);
        when(results.finishAndPublish(row, "SYSTEM_ERROR", 0,
                "language not supported on this node: java", 0L)).thenReturn(true);

        engine.judge(SUBMISSION_ID);

        verify(results).finishAndPublish(row, "SYSTEM_ERROR", 0,
                "language not supported on this node: java", 0L);
        verifyNoInteractions(sandbox);
        verifyNoInteractions(problems);
    }

    @Test
    @DisplayName("submission 已是终态（markRunning 返回 0）时直接跳过，不编译不判题")
    void alreadyFinishedSubmissionIsSkipped() {
        when(submissions.markRunning(SUBMISSION_ID)).thenReturn(0);

        engine.judge(SUBMISSION_ID);

        verifyNoInteractions(languages);
        verifyNoInteractions(sandbox);
        verifyNoInteractions(results);
        verify(submissions, never()).source(SUBMISSION_ID);
    }
}
