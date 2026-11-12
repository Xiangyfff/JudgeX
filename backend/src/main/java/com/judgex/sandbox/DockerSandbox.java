package com.judgex.sandbox;

import com.judgex.config.JudgexProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Container sandbox: no network, read-only rootfs, tmpfs scratch, capped memory,
 * CPU and pids. This is the runner we WANT in production; the local one exists so
 * the project runs on a laptop without Docker Desktop.
 */
@Component
public class DockerSandbox implements SandboxRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerSandbox.class);

    private final JudgexProps props;
    private volatile Boolean cachedAvailable;
    private volatile long checkedAt;

    public DockerSandbox(JudgexProps props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "docker";
    }

    @Override
    public boolean available() {
        long now = System.currentTimeMillis();
        if (cachedAvailable != null && now - checkedAt < 30_000) {
            return cachedAvailable;
        }
        checkedAt = now;
        try {
            Process p = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}")
                    .redirectErrorStream(true).start();
            boolean ok = p.waitFor(4, TimeUnit.SECONDS) && p.exitValue() == 0;
            cachedAvailable = ok;
        } catch (Exception e) {
            cachedAvailable = false;
        }
        return cachedAvailable;
    }

    @Override
    public ExecResult exec(List<String> command, Path workDir, String stdin, int timeoutMs,
                           int memoryMb, int maxOutputBytes) {
        if (!available()) {
            return ExecResult.systemError("docker daemon unavailable");
        }
        List<String> docker = new ArrayList<>(List.of(
                "docker", "run", "--rm", "-i",
                "--network", "none",
                "--memory", memoryMb + "m",
                "--memory-swap", memoryMb + "m",
                "--cpus", "1.0",
                "--pids-limit", "64",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--read-only",
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
                "-v", workDir.toAbsolutePath() + ":/work:rw",
                "-w", "/work",
                "-e", "HOME=/tmp",
                props.sandbox().dockerImage()
        ));
        docker.addAll(command);

        long start = System.nanoTime();
        try {
            Process p = new ProcessBuilder(docker).start();
            try (var os = p.getOutputStream()) {
                if (stdin != null) {
                    os.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            }
            // Drain stderr on its own thread: reading stdout to EOF first deadlocks the moment
            // the child fills the stderr pipe buffer, and the timeout would then fire on a
            // program that was never actually slow.
            StringBuilder errBuf = new StringBuilder();
            Thread errReader = new Thread(() -> {
                String text = readCapped(p.getErrorStream(), maxOutputBytes);
                synchronized (errBuf) {
                    errBuf.append(text);
                }
            }, "docker-stderr");
            errReader.setDaemon(true);
            errReader.start();
            String out = readCapped(p.getInputStream(), maxOutputBytes);
            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
            }
            errReader.join(2000);
            String err;
            synchronized (errBuf) {
                err = errBuf.toString();
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            return new ExecResult(finished ? p.exitValue() : -1, out, err, elapsed, 0, !finished, false, null);
        } catch (IOException e) {
            return ExecResult.systemError("docker exec failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecResult.systemError("interrupted");
        }
    }

    private String readCapped(java.io.InputStream in, int max) {
        try {
            byte[] bytes = in.readNBytes(max);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public void logFallback() {
        log.warn("docker sandbox requested but daemon is unreachable; falling back to local-process sandbox");
    }
}
