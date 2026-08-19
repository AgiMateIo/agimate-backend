package ru.agimate.controlapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * How this service reaches user-api: the address and the key it presents there. Named after the
 * peer rather than after the errand — the next call to user-api reuses both instead of inventing a
 * second name for the same host.
 */
@Component
@ConfigurationProperties(prefix = "app.user-api")
@Getter
@Setter
public class UserApiProperties {

    /** Includes the context path: {@code http://user-api:8080/user}. */
    private String url = "";

    /**
     * The shared secret, as user-api keeps it — no hashing, no second serialization. Unlike the
     * worker pool keys this deliberately does not imitate: there the holders are many and each has
     * its own key, so a hash on the verifying side buys something. Here two of our services share one
     * secret, and the config that would have to leak also holds the OAuth client secrets and the
     * database password — the hash would guard the cheapest thing in the vault.
     */
    private String s2sKey = "";

    public boolean isConfigured() {
        return !url.isBlank() && !s2sKey.isBlank();
    }

    /** Exactly one of the two filled in is a typo, not a choice — see {@code NotificationClient}. */
    public boolean isHalfConfigured() {
        return url.isBlank() != s2sKey.isBlank();
    }
}
