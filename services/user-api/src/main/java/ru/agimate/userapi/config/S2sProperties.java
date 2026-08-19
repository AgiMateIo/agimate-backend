package ru.agimate.userapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The shared secret our own services present when calling {@code /internal/**}. The same value the
 * caller holds — no hash, no second serialization: two of our services share one secret, and the
 * config that would have to leak for the hash to matter also holds the OAuth client secrets and the
 * database password.
 *
 * <p>Empty = the internal surface authenticates nobody. That is the right default: an installation
 * that has not issued the key has no caller for it either.
 */
@Component
@ConfigurationProperties(prefix = "app.s2s")
@Getter
@Setter
public class S2sProperties {

    private String key = "";
}
