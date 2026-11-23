package com.judgex.service;

import com.judgex.sandbox.ExecResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermetic tests for the verdict pipeline: output normalization, single-case
 * classification and the dominance order across cases.
 */
class JudgeEngineTest {

    /** classify()/dominant()/shownMs() touch no instance state, so null collaborators are safe. */
    private final JudgeEngine engine = new JudgeEngine(null, null, null, null, null, null, null, null);

    private static ExecResult result(int exitCode, String stdout, String stderr,
                                     long timeMs, long runMs, boolean timedOut, boolean overflow) {
        return new ExecResult(exitCode, stdout, stderr, timeMs, runMs, 1024, timedOut, overflow, null);
    }

    private static ExecResult ok(String stdout) {
        return result(0, stdout, "", 50, 50, false, false);
    }

    // ---------------------------------------------------------------- normalize

    @Test
    @DisplayName("normalize 统一 CRLF/CR 并在行尾去除空白，再裁掉末尾换行")
    void normalizeUnifiesLineEndingsAndStripsTrailingWhitespace() {
        assertThat(JudgeEngine.normalize("hello\r\nworld\r\n")).isEqualTo("hello\nworld");
        assertThat(JudgeEngine.normalize("a  \r\n b\t \r\n")).isEqualTo("a\n b");
        assertThat(JudgeEngine.normalize("a\rb\r")).isEqualTo("a\nb");
    }

    @Test
    @DisplayName("normalize 保留内部空行，但全空白的尾行会被裁掉")
    void normalizeKeepsInnerBlankLinesAndDropsTrailingBlankLines() {
        assertThat(JudgeEngine.normalize("a\n\nb\n\n\n")).isEqualTo("a\n\nb");
        assertThat(JudgeEngine.normalize("a\n \n\n")).isEqualTo("a");
    }

    @Test
    @DisplayName("normalize 不做前导空白裁剪：行首空格仍然参与比较")
    void normalizeKeepsLeadingWhitespace() {
        assertThat(JudgeEngine.normalize("  a \n")).isEqualTo("  a");
    }

    @Test
    @DisplayName("normalize(null) 视为空串")
    void normalizeNullIsEmpty() {
        assertThat(JudgeEngine.normalize(null)).isEmpty();
    }

    // ---------------------------------------------------------------- classify

    @Test
    @DisplayName("sandbox error 压过 OLE/TLE，直接 SYSTEM_ERROR")
    void sandboxErrorWins() {
        ExecResult r = new ExecResult(-1, "", "", 0, -1, 0, true, true, "docker unavailable");
        assertThat(engine.classify(r, "", 1000)).isEqualTo("SYSTEM_ERROR");
    }

    @Test
    @DisplayName("输出溢出优先于超时：OLE 而不是 TLE")
    void outputOverflowBeatsTimeout() {
        ExecResult r = result(-1, "", "", 999, -1, true, true);
        assertThat(engine.classify(r, "", 1000)).isEqualTo("OLE");
    }

    @Test
    @DisplayName("保险丝切断且 runMs 未知 -> TLE")
    void fuseCutWithUnknownRuntimeIsTle() {
        ExecResult r = result(-1, "", "", 1200, -1, true, false);
        assertThat(engine.classify(r, "", 1000)).isEqualTo("TLE");
    }

    @Test
    @DisplayName("程序自计时超过 limit + 100ms 抖动容忍 -> TLE，边界值不判 TLE")
    void measuredRuntimeUsesJitterTolerance() {
        assertThat(engine.classify(result(0, "x", "", 2000, 2000, false, false), "x", 1000))
                .isEqualTo("TLE");
        assertThat(engine.classify(result(0, "x", "", 1000, 1000, false, false), "x", 1000))
                .isEqualTo("AC");
        assertThat(engine.classify(result(0, "x", "", 1100, 1100, false, false), "x", 1000))
                .isEqualTo("AC");
        assertThat(engine.classify(result(0, "x", "", 1101, 1101, false, false), "x", 1000))
                .isEqualTo("TLE");
    }

    @Test
    @DisplayName("退出码 137（128+SIGKILL）映射为 MLE")
    void exit137IsMle() {
        assertThat(engine.classify(result(137, "", "", 10, 10, false, false), "", 1000))
                .isEqualTo("MLE");
    }

    @Test
    @DisplayName("非 137 退出码按 stderr 的 OOM 特征区分 MLE / RE")
    void nonZeroExitSplitsMleFromRe() {
        assertThat(engine.classify(result(1, "", "java.lang.OutOfMemoryError: Java heap space", 10, 10, false, false),
                "", 1000)).isEqualTo("MLE");
        assertThat(engine.classify(result(1, "", "MemoryError", 10, 10, false, false), "", 1000))
                .isEqualTo("MLE");
        assertThat(engine.classify(result(1, "", "Segmentation fault", 10, 10, false, false), "", 1000))
                .isEqualTo("RE");
        assertThat(engine.classify(result(1, "", "", 10, 10, false, false), "", 1000))
                .isEqualTo("RE");
    }

    @Test
    @DisplayName("超时优先于退出码：慢且 137 仍判 TLE")
    void timeoutBeatsExitCode() {
        assertThat(engine.classify(result(137, "", "", 3000, 3000, false, false), "", 1000))
                .isEqualTo("TLE");
    }

    @Test
    @DisplayName("尾随空白/CRLF 不影响 AC，行首空白或内容不同才是 WA")
    void normalizationDrivesAcAndWa() {
        assertThat(engine.classify(ok("42  \r\n"), "42\n", 1000)).isEqualTo("AC");
        assertThat(engine.classify(ok("42\r\n"), "42", 1000)).isEqualTo("AC");
        assertThat(engine.classify(ok(" 42\n"), "42", 1000)).isEqualTo("WA");
        assertThat(engine.classify(ok("43\n"), "42", 1000)).isEqualTo("WA");
    }

    @Test
    @DisplayName("stdout 为 null 时按空串比较")
    void nullStdoutComparesAsEmpty() {
        assertThat(engine.classify(result(0, null, "", 1, 1, false, false), "", 1000))
                .isEqualTo("AC");
    }

    // ---------------------------------------------------------------- dominant

    @Test
    @DisplayName("总判定优先级：SYSTEM_ERROR > TLE > MLE > OLE > RE > WA")
    void overallVerdictPriority() {
        assertThat(JudgeEngine.dominant(List.of("WA", "OLE", "MLE", "RE"))).isEqualTo("MLE");
        assertThat(JudgeEngine.dominant(List.of("RE", "TLE", "MLE"))).isEqualTo("TLE");
        assertThat(JudgeEngine.dominant(List.of("OLE", "WA", "RE"))).isEqualTo("OLE");
        assertThat(JudgeEngine.dominant(List.of("WA", "RE"))).isEqualTo("RE");
        assertThat(JudgeEngine.dominant(List.of("WA", "TLE", "SYSTEM_ERROR"))).isEqualTo("SYSTEM_ERROR");
        assertThat(JudgeEngine.dominant(List.of("WA"))).isEqualTo("WA");
    }

    // ---------------------------------------------------------------- shownMs

    @Test
    @DisplayName("展示用时：runMs 已知时用它，未知且被保险丝切断时展示 limit 而非 fuse 耗时")
    void shownMsPrefersProgramRuntimeAndLimitOnFuseCut() {
        assertThat(JudgeEngine.shownMs(result(0, "", "", 999, 42, false, false), 1000)).isEqualTo(42L);
        // 未知且不是超时：退回 wall clock
        assertThat(JudgeEngine.shownMs(result(0, "", "", 777, -1, false, false), 1000)).isEqualTo(777L);
        // 超时但输出没有溢出：程序从未跑完，展示它确定超过的 limit
        assertThat(JudgeEngine.shownMs(result(-1, "", "", 1500, -1, true, false), 1000)).isEqualTo(1000L);
        // 输出溢出是另一回事：进程很快就淹死了，wall clock 有意义
        assertThat(JudgeEngine.shownMs(result(-1, "", "", 88, -1, true, true), 1000)).isEqualTo(88L);
    }
}
