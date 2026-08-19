package ru.agimate.controlapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * What this service puts into a notification (docs/decisions/push-notifications.md). Devices, tokens
 * and transports belong to user-api; whom to call and with what key is {@link UserApiProperties} and
 * {@link S2sProperties}.
 */
@Component
@ConfigurationProperties(prefix = "app.notifications")
@Getter
@Setter
public class NotificationProperties {

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
}
