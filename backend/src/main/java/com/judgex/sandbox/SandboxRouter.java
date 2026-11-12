package com.judgex.sandbox;

import com.judgex.config.JudgexProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Component
@Primary
public class SandboxRouter implements SandboxRunner {

    private static final Logger log = LoggerFactory.getLogger(SandboxRouter.class);

    private final DockerSandbox docker;
    private final LocalSandbox local;
    private final SandboxRunner active;

    public SandboxRouter(JudgexProps props, DockerSandbox docker, LocalSandbox local) {
        this.docker = docker;
        this.local = local;
        String mode = props.sandbox().mode() == null ? "auto" : props.sandbox().mode();
        SandboxRunner chosen;
        switch (mode) {
            case "docker" -> {
                if (docker.available()) {
                    chosen = docker;
                } else {
                    docker.logFallback();
                    chosen = local;
                }
            }
            case "local" -> chosen = local;
            default -> chosen = docker.available() ? docker : local;
        }
        this.active = chosen;
        log.info("sandbox runner = {} (requested: {})", active.name(), mode);
    }

    @Override
    public String name() {
        return active.name();
    }

    @Override
    public boolean available() {
        return active.available();
    }

    @Override
    public boolean container() {
        return active.container();
    }

    @Override
    public ExecResult exec(List<String> command, Path workDir, String stdin, int timeoutMs,
                           int memoryMb, int maxOutputBytes) {
        return active.exec(command, workDir, stdin, timeoutMs, memoryMb, maxOutputBytes);
    }

    public boolean dockerReachable() {
        return docker.available();
    }
}
