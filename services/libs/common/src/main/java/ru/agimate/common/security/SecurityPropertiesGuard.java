package ru.agimate.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Fail-fast проверка security-критичных свойств при старте сервиса.
 * <p>
 * Вне dev-профилей ({@code local}, {@code test}) перечисленные свойства обязаны быть заданы —
 * иначе сервис молча стартует с пустыми ключами (упавшая аутентификация, шифрование на пустом
 * ключе) и проблема всплывает только в рантайме. Дефолтов для ключей в {@code application.yaml}
 * нет намеренно: боевые значения приходят только из env (relaxed binding).
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
