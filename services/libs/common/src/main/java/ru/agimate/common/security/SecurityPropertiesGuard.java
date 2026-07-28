package ru.agimate.common.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.List;
import java.util.Locale;

/**
 * Fail-fast validation of security-critical properties at service startup.
 * <p>
 * Outside the dev profiles ({@code local}, {@code test}) the listed properties must be set — otherwise
 * the service starts quietly with empty keys (broken authentication, encryption under an empty key) and
 * the problem surfaces only at runtime. There are deliberately no defaults for the keys in
 * {@code application.yaml}: production values arrive from env alone (relaxed binding).
 * <p>
 * Under a dev profile the same check only warns: a half-configured checkout must still start, but
 * the reason a token is rejected later belongs in the startup log, not in a debugging session.
 */
@Slf4j
@RequiredArgsConstructor
public class SecurityPropertiesGuard implements InitializingBean {

    private static final Profiles DEV_PROFILES = Profiles.of("local", "test");

    private final Environment environment;
    private final List<String> requiredProperties;

    @Override
    public void afterPropertiesSet() {
        List<String> missing = requiredProperties.stream()
                .filter(property -> environment.getProperty(property, "").isBlank())
                .map(property -> property + " (env " + toEnvVar(property) + ")")
                .toList();
        if (missing.isEmpty()) {
            return;
        }

        if (environment.acceptsProfiles(DEV_PROFILES)) {
            log.warn("Security-critical properties are not set: {}. Starting anyway (dev profile) — "
                    + "run ops/dev-init.sh to generate the local configuration",
                    String.join(", ", missing));
            return;
        }
        throw new IllegalStateException("Security-critical properties are not set for a non-dev profile: "
                + String.join(", ", missing));
    }

    private static String toEnvVar(String property) {
        return property.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
