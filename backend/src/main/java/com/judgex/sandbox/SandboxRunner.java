package com.judgex.sandbox;

import java.nio.file.Path;
import java.util.List;

public interface SandboxRunner {

    /** Human readable name for the dashboard ("local-process" / "docker"). */
    String name();

    /** Whether this runner can actually execute work on this machine. */
    boolean available();

    ExecResult exec(List<String> command, Path workDir, String stdin, int timeoutMs, int memoryMb, int maxOutputBytes);
}
