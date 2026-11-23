package com.judgex.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Shouts about the settings that are fine on a laptop and dangerous on the internet.
 * Nothing here stops the application: a misconfigured deploy should still boot, but the
 * log must say plainly what is wrong.
 */
@Component
public class StartupGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupGuard.class);

    /** Must match the default in application.yml. */
    private static final String DEV_SECRET =
            "judgex-dev-secret-please-change-in-production-0123456789";

    private final JudgexProps props;

    public StartupGuard(JudgexProps props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            check();
        } catch (RuntimeException e) {
            log.debug("startup check skipped: {}", e.toString());
        }
    }

    private void check() {
        String mode = props.sandbox() == null ? null : props.sandbox().mode();
        if (mode == null || mode.equalsIgnoreCase("local")) {
            log.warn("sandbox mode is 'local': submitted code runs as this process's user, "
                    + "with no network isolation and no memory limit. Fine for a laptop, "
                    + "not for a public host. Set JUDGEX_SANDBOX=docker before exposing the service.");
        }
        if (DEV_SECRET.equals(props.jwtSecret())) {
            log.warn("judgex.jwt-secret is still the built-in development value, which is public "
                    + "in the repository; anyone could mint an admin token. Set JUDGEX_JWT_SECRET "
                    + "to a random string of 32+ characters.");
        }
    }
}
