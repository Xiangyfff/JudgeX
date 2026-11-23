package com.judgex.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Hermetic tests for the runMs fallback that JudgeEngine relies on. */
class ExecResultTest {

    @Test
    @DisplayName("runMs 已知时 effectiveMs 用程序自身耗时")
    void effectiveMsUsesProgramRuntime() {
        ExecResult r = new ExecResult(0, "out", "", 1200, 35, 512, false, false, null);

        assertThat(r.effectiveMs()).isEqualTo(35L);
    }

    @Test
    @DisplayName("runMs = -1 时回退到 wall clock timeMs")
    void effectiveMsFallsBackToWallClock() {
        ExecResult r = new ExecResult(-1, "", "", 1500, -1, 0, true, false, null);

        assertThat(r.effectiveMs()).isEqualTo(1500L);
    }

    @Test
    @DisplayName("systemError 的错误非空、runMs=-1、effectiveMs=0")
    void systemErrorCarriesErrorAndZeroTime() {
        ExecResult r = ExecResult.systemError("docker failed");

        assertThat(r.error()).isEqualTo("docker failed");
        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(r.runMs()).isEqualTo(-1L);
        assertThat(r.timeMs()).isZero();
        assertThat(r.effectiveMs()).isZero();
        assertThat(r.timedOut()).isFalse();
        assertThat(r.outputOverflow()).isFalse();
    }
}
