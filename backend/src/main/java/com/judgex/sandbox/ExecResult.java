package com.judgex.sandbox;

/**
 * Outcome of a single sandboxed process execution.
 *
 * <p>{@code timeMs} is the wall clock the runner spent, fences included: it is what the
 * outer fuse measures, not what the submission should be judged on. {@code runMs} is the
 * wall clock the program itself spent inside the sandbox (startup of the container and of
 * the wrapper shell excluded), and is {@code -1} when it could not be measured — typically
 * because the fuse killed the process before it wrote its end timestamp.
 */
public record ExecResult(
        int exitCode,
        String stdout,
        String stderr,
        long timeMs,
        /** Program runtime only; -1 when unknown. */
        long runMs,
        int memoryKb,
        boolean timedOut,
        boolean outputOverflow,
        String error
) {
    /** Runtime to judge and display: the program's own time, falling back to wall clock. */
    public long effectiveMs() {
        return runMs >= 0 ? runMs : timeMs;
    }

    public static ExecResult systemError(String message) {
        return new ExecResult(-1, "", "", 0, -1, 0, false, false, message);
    }
}
