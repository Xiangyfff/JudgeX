package com.judgex.sandbox;

import java.nio.file.Path;
import java.util.List;

public interface SandboxRunner {

    /** Human readable name for the dashboard ("local-process" / "docker"). */
    String name();

    /** Whether this runner can actually execute work on this machine. */
    boolean available();

    /**
     * True when the command runs inside a container, so it must be expressed with
     * names that exist in the image ("python3"), not with host paths
     * ("C:\...\python.exe").
     */
    default boolean container() {
        return false;
    }

    ExecResult exec(List<String> command, Path workDir, String stdin, int timeoutMs, int memoryMb, int maxOutputBytes);
}
