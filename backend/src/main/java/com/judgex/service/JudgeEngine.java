package com.judgex.service;

import com.judgex.config.JudgexProps;
import com.judgex.repo.ProblemMapper;
import com.judgex.repo.SubmissionMapper;
import com.judgex.redis.LeaderboardService;
import com.judgex.sandbox.ExecResult;
import com.judgex.sandbox.LanguageRegistry;
import com.judgex.sandbox.SandboxRunner;
import com.judgex.web.dto.Dto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Compile once, run every test case, aggregate a verdict.
 * Every state change is persisted before it is published, so a crashed worker
 * can be replayed from the database instead of from memory.
 */
@Service
public class JudgeEngine {

    private static final Logger log = LoggerFactory.getLogger(JudgeEngine.class);

    /**
     * Millisecond slack before a measured runtime counts as TLE. It covers scheduling jitter,
     * the wrapper's fork/exec and millisecond truncation — deliberately tiny, because the
     * startup cost it used to hide is now excluded before the comparison.
     */
    private static final int JITTER_TOLERANCE_MS = 100;

    private final SubmissionMapper submissions;
    private final ProblemMapper problems;
    private final LanguageRegistry languages;
    private final SandboxRunner sandbox;
    private final ProgressHub hub;
    private final JudgexProps props;
    private final LeaderboardService leaderboard;
    private final ResultService results;

    public JudgeEngine(SubmissionMapper submissions, ProblemMapper problems, LanguageRegistry languages,
                       SandboxRunner sandbox, ProgressHub hub, JudgexProps props,
                       LeaderboardService leaderboard, ResultService results) {
        this.submissions = submissions;
        this.problems = problems;
        this.languages = languages;
        this.sandbox = sandbox;
        this.hub = hub;
        this.props = props;
        this.leaderboard = leaderboard;
        this.results = results;
    }

    public void judge(long submissionId) {
        MDC.put("traceId", "judge-" + submissionId);
        long startedAt = System.currentTimeMillis();
        Path workDir = null;
        try {
            Dto.SubmissionRow row = submissions.find(submissionId).orElse(null);
            if (row == null) {
                log.warn("submission {} vanished before judging", submissionId);
                return;
            }
            // Replays reach here too (reclaimed lease, duplicate dispatch). A submission that is
            // already terminal has an answer the user saw; re-running it can only corrupt it.
            if (submissions.markRunning(submissionId) == 0) {
                log.info("submission {} already finished, skipping replay", submissionId);
                return;
            }
            hub.publish(submissionId, "status", Map.of("status", "RUNNING", "submissionId", submissionId));

            LanguageRegistry.Language lang = languages.get(row.language());
            if (lang == null) {
                finish(submissionId, "SYSTEM_ERROR", 0, "language not supported on this node: " + row.language(), 0);
                return;
            }

            String source = submissions.source(submissionId);
            List<ProblemMapper.JudgeCase> cases = problems.judgeCases(row.problemId());
            if (cases.isEmpty()) {
                finish(submissionId, "SYSTEM_ERROR", 0, "problem has no test cases", 0);
                return;
            }
            // Function-style problems ship only the solution class; the harness turns it into
            // a program that reads one line of arguments and prints the return value.
            ProblemMapper.JudgeCase first = cases.get(0);
            if ("FUNCTION".equalsIgnoreCase(first.judgeMode())) {
                String wrapped = FunctionHarness.wrap(row.language(), first.signature(), source);
                if (wrapped == null) {
                    finish(submissionId, "SYSTEM_ERROR", 0,
                            "function-style judging is not supported for " + row.language()
                                    + " (signature: " + first.signature() + ")", 0);
                    return;
                }
                source = wrapped;
            }
            workDir = Files.createDirectories(Path.of(props.sandbox().workDir(), "sub-" + submissionId));
            Files.writeString(workDir.resolve(lang.extension()), source, StandardCharsets.UTF_8);

            if (lang.needsCompile()) {
                ExecResult compile = sandbox.exec(lang.compileCommand(sandbox.container()), workDir, "",
                        30_000 + allowanceMs(), 512, props.sandbox().maxOutputBytes());
                String compileLog = combined(compile);
                long compileTimeMs = compile.effectiveMs();
                if (compile.error() != null) {
                    finish(submissionId, "SYSTEM_ERROR", 0, compileLog, 0);
                    return;
                }
                if (compile.timedOut() || compile.exitCode() != 0) {
                    finish(submissionId, "CE", 0, compileLog.isBlank() ? "compilation failed" : compileLog,
                            compileTimeMs);
                    return;
                }
                hub.publish(submissionId, "compiled", Map.of("log", compileLog, "timeMs", compileTimeMs));
            }

            int passed = 0;
            int score = 0;
            long totalTime = 0;
            List<String> verdicts = new ArrayList<>();

            for (ProblemMapper.JudgeCase c : cases) {
                // Limits belong to the problem; the engine only supplies fallbacks for old rows.
                int memoryMb = c.memoryLimitMb() > 0 ? c.memoryLimitMb() : 512;
                int limitMs = c.timeLimitMs() > 0 ? c.timeLimitMs() : 2000;
                // The outer timer is a fuse: limit + boot headroom. It kills a hung run, but the
                // verdict comes from the runtime measured inside the sandbox (r.runMs()).
                int timeoutMs = limitMs + allowanceMs();
                ExecResult r = sandbox.exec(lang.runCommand(memoryMb, sandbox.container()), workDir, c.input(),
                        timeoutMs, memoryMb, props.sandbox().maxOutputBytes());

                // What the user sees is the program's own runtime, never the container boot.
                long effective = shownMs(r, limitMs);
                String verdict = classify(r, c.expectedOutput(), limitMs);
                totalTime += effective;
                if ("AC".equals(verdict)) {
                    passed++;
                    score += c.points();
                } else {
                    submissions.addResult(submissionId, c.id(), c.ordinal(), verdict,
                            (int) effective, r.memoryKb(),
                            preview(r.stdout()), preview(r.stderr()), 0);
                    verdicts.add(verdict);
                    hub.publish(submissionId, "case", Map.of(
                            "ordinal", c.ordinal(),
                            "verdict", verdict,
                            "timeMs", effective,
                            "passed", passed,
                            "total", cases.size()));
                    continue;
                }
                submissions.addResult(submissionId, c.id(), c.ordinal(), "AC",
                        (int) effective, r.memoryKb(), preview(r.stdout()), preview(r.stderr()), c.points());
                hub.publish(submissionId, "case", Map.of(
                        "ordinal", c.ordinal(),
                        "verdict", "AC",
                        "timeMs", effective,
                        "passed", passed,
                        "total", cases.size()));
            }

            // Partial credit is intentional: score is the sum of passed case points.
            String overall = verdicts.isEmpty() ? "AC" : dominant(verdicts);
            finish(submissionId, overall, score, null, totalTime);
            log.info("judged submission {} verdict={} score={} in {}ms (worker {})",
                    submissionId, overall, score, System.currentTimeMillis() - startedAt,
                    Thread.currentThread().getName());
        } catch (Exception e) {
            log.error("judge failed for submission {}: {}", submissionId, e.toString(), e);
            try {
                finish(submissionId, "SYSTEM_ERROR", 0, "judge crashed: " + e.getMessage(), 0);
            } catch (Exception ignored) {
                // nothing else we can do
            }
        } finally {
            deleteQuietly(workDir);
            MDC.remove("traceId");
        }
    }

    private void finish(long submissionId, String verdict, int score, String compileOutput, long totalTime) {
        submissions.find(submissionId).ifPresent(row -> {
            // Verdict + outbox row commit together; delivery to the broker happens later.
            // A submission that already reached a terminal state must not be overwritten by a
            // late result (replayed task, expired lease, duplicated execution).
            if (!results.finishAndPublish(row, verdict, score, compileOutput, totalTime)) {
                log.info("submission {} already finished, late {} result dropped", submissionId, verdict);
                return;
            }
            List<Dto.TestResultRow> results = submissions.results(submissionId);
            hub.finish(submissionId, Map.of(
                    "row", row,
                    "cases", results,
                    "compileOutput", compileOutput == null ? "" : compileOutput));
            if ("AC".equals(verdict)) {
                // Ranking is derived data: never let a Redis hiccup fail a judgement.
                try {
                    leaderboard.refresh(row.userId(), row.user());
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        });
    }

    /** Fuse headroom: container boot in docker mode, process spawn in local mode. */
    private int allowanceMs() {
        JudgexProps.Sandbox sb = props.sandbox();
        int configured = sandbox.container() ? sb.startupAllowanceMs() : sb.localStartupAllowanceMs();
        return Math.max(0, configured);
    }

    /**
     * Time to show the user. A run cut by the fuse never finished, so its own runtime is
     * unknown; the honest number is the limit it certainly exceeded, not the fuse delay
     * (limit + boot headroom), which would credit the container's boot to the program.
     * Output overflow is different: the process drowned quickly and the wall clock says when.
     */
    static long shownMs(ExecResult r, int limitMs) {
        if (r.runMs() < 0 && r.timedOut()) {
            return r.outputOverflow() ? r.timeMs() : limitMs;
        }
        return r.effectiveMs();
    }

    String classify(ExecResult r, String expected, int limitMs) {
        if (r.error() != null) {
            return "SYSTEM_ERROR";
        }
        // Overflow is checked before the timeout: when output explodes we kill the process
        // ourselves, so the run also looks "not finished". The cause is the output, not speed.
        if (r.outputOverflow()) {
            return "OLE";
        }
        if (r.runMs() < 0 && r.timedOut()) {
            // The fuse cut the run before the program stamped its end: the runtime is unknown
            // and the process never returned, which is a genuine timeout.
            return "TLE";
        }
        // Only the program's own time counts. The small tolerance absorbs scheduling jitter and
        // the wrapper's own fork/exec, not container boot.
        if (r.effectiveMs() > limitMs + JITTER_TOLERANCE_MS) {
            return "TLE";
        }
        if (r.exitCode() != 0) {
            // 137 = 128 + SIGKILL: the container's OOM killer ended it, so the program did
            // hit the memory ceiling even though it never printed a message of its own.
            if (r.exitCode() == 137) {
                return "MLE";
            }
            return looksLikeOutOfMemory(r.stderr()) ? "MLE" : "RE";
        }
        return normalize(r.stdout()).equals(normalize(expected)) ? "AC" : "WA";
    }

    /**
     * Local mode cannot measure resident memory, so the runtime's own out-of-memory message is
     * the only honest signal that the heap cap was hit. The container sandbox reports it through
     * a non-zero exit as well, and the same patterns match.
     */
    private static boolean looksLikeOutOfMemory(String stderr) {
        if (stderr == null || stderr.isEmpty()) {
            return false;
        }
        return stderr.contains("OutOfMemoryError")
                || stderr.contains("JavaScript heap out of memory")
                || stderr.contains("MemoryError")
                || stderr.contains("std::bad_alloc");
    }

    static String dominant(List<String> verdicts) {
        List<String> priority = List.of("SYSTEM_ERROR", "TLE", "MLE", "OLE", "RE", "WA");
        for (String p : priority) {
            if (verdicts.contains(p)) {
                return p;
            }
        }
        return verdicts.get(0);
    }

    /** Trailing whitespace and final newlines never decide a verdict. */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String unified = s.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder(unified.length());
        for (String line : unified.split("\n", -1)) {
            sb.append(line.stripTrailing()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String combined(ExecResult r) {
        StringBuilder sb = new StringBuilder();
        if (r.stdout() != null && !r.stdout().isBlank()) {
            sb.append(r.stdout());
        }
        if (r.stderr() != null && !r.stderr().isBlank()) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(r.stderr());
        }
        String s = sb.toString().strip();
        return s.length() > 4000 ? s.substring(0, 4000) + "\n…" : s;
    }

    private static String preview(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 2000 ? s.substring(0, 2000) + "\n…" : s;
    }

    private void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
