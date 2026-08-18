package ru.agimate.controlapi.service.notification;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.agimate.controlapi.config.NotificationProperties;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Hands a notification over to user-api, which owns the devices. This service never sees a push
 * token — it says whom to notify and what to say (docs/decisions/push-notifications.md).
 *
 * <p>Never throws: the caller has just delivered a message that is already written and published,
 * and a notification lost on the way is repaired by the next one.
 */
@Slf4j
@Component
public class NotificationClient {

    private static final String INTERNAL_AUTH_KEY_HEADER = "X-Internal-Auth-Key";
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final NotificationProperties properties;
    private final RestClient restClient;

    public NotificationClient(NotificationProperties properties) {
        this.properties = properties;
        // An explicit factory, as in TelegramApiClient: the default builder picks one by classpath
        // detection, which HttpComponents wins by arriving transitively with the AWS SDK.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Half-filled configuration fails the boot. Empty is a decision — this installation does not
     * notify — while one of the two is a typo whose only symptom is a notification that never comes.
     */
    @PostConstruct
    void checkConfiguration() {
        if (properties.isHalfConfigured()) {
            throw new IllegalStateException(
                    "app.notifications: base-url and auth-token must be set together — one of them is empty");
        }
        if (!properties.isConfigured()) {
            log.info("app.notifications is not configured — notifications are not handed over to user-api");
        }
    }

    public void notifyUser(UUID userId, Map<String, String> data) {
        if (!properties.isConfigured()) {
            return;
        }
        try {
            restClient.post()
                    .uri(properties.getBaseUrl() + "/internal/notifications")
                    .header(INTERNAL_AUTH_KEY_HEADER, properties.getAuthToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "userId", userId.toString(),
                            "data", data,
                            "ttlSeconds", properties.getTtl().toSeconds()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // The class of the exception only: RestClient puts the URL into the message, and the
            // cause may carry the header with the key.
            log.warn("user-api did not accept a notification for user {}: {}",
                    userId, e.getClass().getSimpleName());
        }
    }
}
