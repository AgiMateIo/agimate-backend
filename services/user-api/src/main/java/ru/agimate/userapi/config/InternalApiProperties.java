package ru.agimate.userapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The key that lets control-api call {@code /internal/**} (docs/contracts/api-keys.md). Stored as an
 * authkey — prefix, key id and the hash of the secret — so a config dump does not yield a usable
 * key; control-api holds the 64-char full key.
 *
 * <p>Empty = the internal surface authenticates nobody and answers 401 to everything. That is the
 * right default: an installation that has not issued the key has no caller for it either.
 */
@Component
@ConfigurationProperties(prefix = "app.internal")
@Getter
@Setter
public class InternalApiProperties {

    private String authkey = "";
}
