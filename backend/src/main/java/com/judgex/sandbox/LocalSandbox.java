package com.judgex.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-level sandbox used when Docker is unavailable.
 *
 * It still enforces the two limits that matter for a judge — wall clock and
 * output size — and scrubs the environment so submitted code cannot read host
 * secrets through env vars. It does NOT provide kernel-level isolation; the
 * threat model is documented in docs/SANDBOX.md.
 */
@Component
public class LocalSandbox implements SandboxRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalSandbox.class);

    @Override
    public String name() {
        return "local-process";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public ExecResult exec(List<String> command, Path workDir, String stdin, int timeoutMs,
                           int memoryMb, int maxOutputBytes) {
        long start = System.nanoTime();
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            scrubEnvironment(pb.environment(), workDir);
            pb.redirectErrorStream(false);
            process = pb.start();
        } catch (IOException e) {
            return ExecResult.systemError("cannot start process: " + e.getMessage());
        }

        AtomicBoolean overflow = new AtomicBoolean(false);
        AtomicBoolean done = new AtomicBoolean(false);
        Set<ProcessHandle> tracked = ConcurrentHashMap.newKeySet();

        // Submitted code can spawn children that outlive the parent; on Windows
        // killing the parent leaves them running and holding the pipe open, which
        // both burns the timeout budget and keeps the work dir locked. Sample the
        // tree while it is alive so it can be reaped afterwards.
        Thread reaper = new Thread(() -> {
            while (!done.get()) {
                process.descendants().forEach(tracked::add);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "sandbox-reaper");
        reaper.setDaemon(true);
        reaper.start();

        Runnable killer = () -> killTree(process, tracked);

        Thread stdinWriter = new Thread(() -> {
            try (var os = process.getOutputStream()) {
                if (stdin != null && !stdin.isEmpty()) {
                    os.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // process died early; nothing useful to do
            }
        }, "sandbox-stdin");
        stdinWriter.setDaemon(true);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread outReader = limitReader(process.getInputStream(), out, maxOutputBytes, overflow, killer, process, "stdout");
        Thread errReader = limitReader(process.getErrorStream(), err, maxOutputBytes, overflow, killer, process, "stderr");

        stdinWriter.start();
        outReader.start();
        errReader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }

        done.set(true);
        reaper.interrupt();
        if (!finished) {
            log.warn("timed out after {} ms, killing process tree of pid {}", timeoutMs, process.pid());
        }
        killer.run();
        try {
            process.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        joinQuietly(stdinWriter);
        joinQuietly(outReader);
        joinQuietly(errReader);

        long elapsed = (System.nanoTime() - start) / 1_000_000;
        int exit = finished ? process.exitValue() : -1;
        return new ExecResult(
                exit,
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8),
                elapsed,
                0,
                !finished,
                overflow.get(),
                null);
    }

    private void scrubEnvironment(Map<String, String> env, Path workDir) {
        String path = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        env.clear();
        if (!path.isBlank()) {
            env.put("PATH", path);
        }
        env.put("HOME", workDir.toString());
        env.put("TMPDIR", workDir.toString());
        env.put("TEMP", workDir.toString());
        env.put("TMP", workDir.toString());
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");
    }

    private Thread limitReader(InputStream in, ByteArrayOutputStream sink, int max,
                               AtomicBoolean overflow, Runnable killer, Process process, String label) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            long total = 0;
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (total >= max) {
                        if (overflow.compareAndSet(false, true)) {
                            log.warn("{} exceeded {} bytes, killing pid {}", label, max, process.pid());
                            killer.run();
                        }
                        total += n;
                        continue;
                    }
                    int write = (int) Math.min(n, max - total);
                    sink.write(buf, 0, write);
                    total += n;
                }
            } catch (IOException ignored) {
                // stream closed on kill
            }
        }, "sandbox-" + label);
        t.setDaemon(true);
        return t;
    }

    private void killTree(Process process, Set<ProcessHandle> tracked) {
        tracked.forEach(ProcessHandle::destroyForcibly);
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private void joinQuietly(Thread t) {
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static boolean isWindows() {
        return File.separatorChar == '\\';
    }
}
