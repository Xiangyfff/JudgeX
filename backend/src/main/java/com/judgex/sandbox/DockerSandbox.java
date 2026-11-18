package com.judgex.sandbox;

import com.judgex.config.JudgexProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Container sandbox: no network, read-only rootfs, tmpfs scratch, capped memory,
 * CPU and pids, dropped capabilities, a whitelist-free seccomp profile that only
 * denies the dangerous syscalls (so JVM, Node and Python keep working), and the
 * program itself runs as nobody instead of root.
 */
@Component
public class DockerSandbox implements SandboxRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerSandbox.class);

    /** Packaged into the jar from docker/runner/seccomp.json at build time. */
    private static final String SECCOMP_RESOURCE = "/sandbox/seccomp.json";

    /** One file may not exceed 64 MiB: /work is a host bind mount with no quota. */
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;

    private final JudgexProps props;
    private volatile Boolean cachedAvailable;
    private volatile long checkedAt;
    /** null = not resolved yet, empty = unavailable, otherwise an absolute path. */
    private volatile String preparedSeccomp;


    public DockerSandbox(JudgexProps props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "docker";
    }

    @Override
    public boolean container() {
        return true;
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
        // Named per run so a timeout can kill the container itself. Destroying only the
        // docker client leaves the container running and keeps the stdout pipe open, which
        // is exactly how a busy loop used to hang the judge forever.
        String containerName = "judgex-" + UUID.randomUUID().toString().substring(0, 12);
        List<String> docker = new ArrayList<>(List.of(
                "docker", "run", "--rm", "-i",
                "--name", containerName,
                "--network", "none",
                "--memory", memoryMb + "m",
                "--memory-swap", memoryMb + "m",
                "--cpus", "1.0",
                "--pids-limit", "64",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges:true",
                "--read-only",
                // No /dev/shm: nothing here needs shared memory, and it removes a
                // writable, executable-friendly region from the container.
                "--ipc", "none",
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
                "--ulimit", "nofile=64:64",
                "--ulimit", "core=0",
                "--ulimit", "fsize=" + MAX_FILE_BYTES
        ));
        String user = props.sandbox().runAsUser();
        if (user != null && !user.isBlank()) {
            docker.add("--user");
            docker.add(user);
        }
        String seccomp = seccompProfile();
        if (seccomp != null) {
            docker.add("--security-opt");
            docker.add("seccomp=" + seccomp);
        }
        docker.addAll(List.of(
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

            StringBuilder outBuf = new StringBuilder();
            StringBuilder errBuf = new StringBuilder();
            boolean[] overflow = {false};
            // Both pipes are drained on their own threads: reading them in the calling thread
            // would block until the program exits, so a program that never exits would never
            // reach the timeout check below.
            Thread outReader = reader("docker-stdout", p.getInputStream(), outBuf, maxOutputBytes, () -> {
                overflow[0] = true;
                kill(p, containerName);
            });
            Thread errReader = reader("docker-stderr", p.getErrorStream(), errBuf, maxOutputBytes, () -> kill(p, containerName));
            outReader.start();
            errReader.start();

            // Wait for the container, but bail out early when the output cap already killed it:
            // otherwise an output flood would burn the whole time limit before being scored.
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            boolean finished = false;
            while (System.nanoTime() < deadline) {
                if (p.waitFor(20, TimeUnit.MILLISECONDS)) {
                    finished = true;
                    break;
                }
                if (overflow[0]) {
                    break;
                }
            }
            if (!finished && p.isAlive()) {
                log.warn("container {} exceeded {} ms (or output cap), killing it", containerName, timeoutMs);
                kill(p, containerName);
            }
            outReader.join(2000);
            errReader.join(2000);

            int exit = -1;
            if (finished) {
                try {
                    exit = p.exitValue();
                } catch (IllegalThreadStateException ignored) {
                    // killed between waitFor returning and reading the exit code
                }
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            return new ExecResult(exit, outBuf.toString(), errBuf.toString(), elapsed, 0, !finished,
                    overflow[0], null);
        } catch (IOException e) {
            return ExecResult.systemError("docker exec failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecResult.systemError("interrupted");
        }
    }

    private Thread reader(String name, InputStream in, StringBuilder sink, int max, Runnable onOverflow) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[4096];
            int total = 0;
            boolean fired = false;
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (total < max) {
                        int take = Math.min(n, max - total);
                        sink.append(new String(buf, 0, take, StandardCharsets.UTF_8));
                    }
                    total += n;
                    if (total >= max && !fired) {
                        fired = true;
                        onOverflow.run();
                    }
                }
            } catch (IOException ignored) {
                // pipe closed because the container was killed
            }
        }, name);
        t.setDaemon(true);
        return t;
    }

    private void kill(Process p, String containerName) {
        // Fire and forget: waiting for the docker CLI here would make every killed case pay
        // the same multi-second timeout that the container itself is being punished for.
        try {
            new ProcessBuilder("docker", "kill", "-s", "KILL", containerName)
                    .redirectErrorStream(true).start();
        } catch (Exception e) {
            log.debug("docker kill {} failed: {}", containerName, e.toString());
        }
        p.destroyForcibly();
    }

    /**
     * Absolute path of the seccomp profile handed to the Docker CLI. Docker on Windows
     * reads this file client-side, so a Windows path is exactly what it needs.
     */
    private String seccompProfile() {
        String prepared = preparedSeccomp;
        if (prepared == null) {
            synchronized (this) {
                if (preparedSeccomp == null) {
                    preparedSeccomp = resolveSeccomp();
                }
                prepared = preparedSeccomp;
            }
        }
        return prepared.isEmpty() ? null : prepared;
    }

    private String resolveSeccomp() {
        String configured = props.sandbox().seccompProfile();
        if (configured != null && !configured.isBlank()) {
            Path candidate = Path.of(configured).toAbsolutePath();
            if (Files.isRegularFile(candidate)) {
                log.info("docker sandbox uses seccomp profile {}", candidate);
                return candidate.toString();
            }
            log.warn("configured seccomp profile {} is missing; using the bundled profile", candidate);
        }
        try (InputStream in = DockerSandbox.class.getResourceAsStream(SECCOMP_RESOURCE)) {
            if (in == null) {
                log.error("bundled seccomp profile {} is missing; containers keep Docker's default profile",
                        SECCOMP_RESOURCE);
                return "";
            }
            byte[] json = in.readAllBytes();
            // Docker wants a path, not the content, and every run rebuilds the argument
            // list, so materialise it once in the temp dir (also outside the read-only judge dirs).
            Path target = Path.of(System.getProperty("java.io.tmpdir"), "judgex-seccomp.json")
                    .toAbsolutePath();
            Files.write(target, json);
            log.info("docker sandbox uses bundled seccomp profile {} ({} bytes)", target, json.length);
            return target.toString();
        } catch (IOException e) {
            log.error("could not prepare the seccomp profile: {}", e.toString());
            return "";
        }
    }

    public void logFallback() {
        log.warn("docker sandbox requested but daemon is unreachable; falling back to local-process sandbox");
    }
}
