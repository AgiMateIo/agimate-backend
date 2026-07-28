package ru.agimate.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Fail-fast validation of security-critical properties at service startup.
 * <p>
 * Outside the dev profiles ({@code local}, {@code test}) the listed properties must be set — otherwise
 * the service starts quietly with empty keys (broken authentication, encryption under an empty key) and
 * the problem surfaces only at runtime. There are deliberately no defaults for the keys in
 * {@code application.yaml}: production values arrive from env alone (relaxed binding).
 */
@RequiredArgsConstructor
public class SecurityPropertiesGuard implements InitializingBean {

    private static final Profiles DEV_PROFILES = Profiles.of("local", "test");

    private final Environment environment;
    private final List<String> requiredProperties;

    @Override
    public void afterPropertiesSet() {
        if (environment.acceptsProfiles(DEV_PROFILES)) {
            return;
        }
        List<String> missing = requiredProperties.stream()
                .filter(property -> environment.getProperty(property, "").isBlank())
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Security-critical properties are not set for a non-dev profile: "
                            + missing.stream()
                                    .map(p -> p + " (env " + toEnvVar(p) + ")")
                                    .collect(Collectors.joining(", ")));
        }
    }

    private static String toEnvVar(String property) {
        return property.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
