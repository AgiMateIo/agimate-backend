package ru.agimate.userapi.service.push.rustore;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.agimate.userapi.config.PushProperties;
import ru.agimate.userapi.database.entities.PushProvider;
import ru.agimate.userapi.service.push.PushDelivery;
import ru.agimate.userapi.service.push.PushMessage;
import ru.agimate.userapi.service.push.PushTransport;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RuStore push (VK PNS): {@code POST /v1/projects/{projectId}/messages:send} with the service token
 * as a bearer, one token per request — the wire format has a single {@code message.token}.
 *
 * <p>Neither a collapse key nor a priority is sent: their field names are not confirmed against the
 * console's documentation, and inventing them would produce a request that looks right and silently
 * drops the fields. The user-visible collapsing is the client's anyway — it rewrites the
 * notification of a conversation it already shows.
 */
@Slf4j
@Component
public class RuStorePushTransport implements PushTransport {

    private static final String BASE_URL = "https://vkpns.rustore.ru";
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final PushProperties pushProperties;
    private final RestClient restClient;

    public RuStorePushTransport(PushProperties pushProperties) {
        this.pushProperties = pushProperties;
        // An explicit factory, as in TelegramApiClient: the default builder picks one by classpath
        // detection, which HttpComponents wins by arriving transitively with the AWS SDK.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder().baseUrl(BASE_URL).requestFactory(factory).build();
    }

    /**
     * Half-filled credentials fail the boot. Empty ones are a decision — this installation does not
     * send — while one of the two is a typo, and the only place it would otherwise surface is a
     * notification that never arrives.
     */
    @PostConstruct
    void checkConfiguration() {
        PushProperties.RuStore rustore = pushProperties.getRustore();
        if (rustore.isHalfConfigured()) {
            throw new IllegalStateException(
                    "app.push.rustore: project-id and service-key must be set together — one of them is empty");
        }
        if (!rustore.isConfigured()) {
            log.info("RuStore push is not configured — subscriptions are stored, notifications are not sent");
        }
    }

    @Override
    public PushProvider provider() {
        return PushProvider.RUSTORE;
    }

    @Override
    public boolean isConfigured() {
        return pushProperties.getRustore().isConfigured();
    }

    @Override
    public PushDelivery send(String token, PushMessage message) {
        try {
            restClient.post()
                    .uri("/v1/projects/{projectId}/messages:send", pushProperties.getRustore().getProjectId())
                    .header("Authorization", "Bearer " + pushProperties.getRustore().getServiceKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body(token, message))
                    .retrieve()
                    .toBodilessEntity();
            return PushDelivery.DELIVERED;
        } catch (RestClientResponseException e) {
            PushDelivery delivery = deliveryFor(e.getStatusCode());
            // The class of the exception only: RestClient puts the URL into the message, and ours
            // carries the project id, while the cause may carry the header with the service key.
            log.debug("RuStore push refused with {} ({})", e.getStatusCode(), delivery);
            return delivery;
        } catch (Exception e) {
            log.warn("RuStore push failed: {}", e.getClass().getSimpleName());
            return PushDelivery.FAILED;
        }
    }

    /**
     * 404 is what the transport answers for a token it no longer knows, 400 for one that is not a
     * token at all; both mean this row will never deliver again. Everything else may recover, and a
     * subscription dropped on a 5xx would take the device with it.
     */
    static PushDelivery deliveryFor(HttpStatusCode status) {
        if (status.value() == 404 || status.value() == 400) {
            return PushDelivery.TOKEN_GONE;
        }
        return PushDelivery.FAILED;
    }

    /**
     * {@code {"message": {"token": …, "data": {…}, "android": {"ttl": "3600s"}}}} — data only. The
     * whole request is capped at 4 KB by the transport, which the preview length keeps us well under.
     */
    private Map<String, Object> body(String token, PushMessage message) {
        Duration ttl = message.ttl() != null ? message.ttl() : pushProperties.getTtl();
        Map<String, Object> android = Map.of("ttl", ttl.toSeconds() + "s");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("token", token);
        payload.put("data", message.data());
        payload.put("android", android);

        return Map.of("message", payload);
    }
}
