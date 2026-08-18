package ru.agimate.controlapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * What this service puts into a notification and where it hands it over
 * (docs/decisions/push-notifications.md). Devices, tokens and transports belong to user-api; here
 * there is only the content and the address of the relay.
 */
@Component
@ConfigurationProperties(prefix = "app.notifications")
@Getter
@Setter
public class NotificationProperties {

    /** user-api base URL including its context path, e.g. {@code http://user-api:8080/user}. */
    private String baseUrl = "";

    /** The 64-char full key; user-api keeps the hash of it in {@code app.internal.authkey}. */
    private String authToken = "";

    /**
     * Whether the answer's first line travels in the notification. It passes through user-api and
     * the transport's infrastructure and is drawn on a locked screen, so an installation with
     * stricter expectations turns it off and gets a notification that only says who wrote.
     */
    private boolean preview = true;

    /**
     * How long the delivery is still worth attempting. An answer that arrives in the evening is of
     * no use to anyone, while the transport's own default is four weeks.
     */
    private Duration ttl = Duration.ofHours(1);

    public boolean isConfigured() {
        return !baseUrl.isBlank() && !authToken.isBlank();
    }

    /** Exactly one of the two filled in is a typo, not a choice — see {@code NotificationClient}. */
    public boolean isHalfConfigured() {
        return baseUrl.isBlank() != authToken.isBlank();
    }
}
