package com.judgex.sandbox;

/** Outcome of a single sandboxed process execution. */
public record ExecResult(
        int exitCode,
        String stdout,
        String stderr,
        long timeMs,
        int memoryKb,
        boolean timedOut,
        boolean outputOverflow,
        String error
) {
    public static ExecResult systemError(String message) {
        return new ExecResult(-1, "", "", 0, 0, false, false, message);
    }
}
