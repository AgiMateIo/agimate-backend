package ru.agimate.userapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * How many people the platform lets in by itself in a day (docs/decisions/signup-quota.md).
 */
@Component
@ConfigurationProperties(prefix = "app.signup")
@Getter
@Setter
public class SignupProperties {

    /**
     * Registrations of the day admitted as {@code USER} without an administrator; the rest are
     * created as {@code GUEST} and wait. Zero admits nobody — the whole gate then works the way it
     * did before the quota existed.
     */
    private int dailyAdmissions = 10;
}
