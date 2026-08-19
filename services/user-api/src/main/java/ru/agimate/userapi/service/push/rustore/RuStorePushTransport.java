package ru.agimate.userapi.service.push.rustore;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.userapi.config.PushProperties;
import ru.agimate.userapi.database.entities.PushProvider;
import ru.agimate.userapi.service.push.PushDelivery;
import ru.agimate.userapi.service.push.PushMessage;
import ru.agimate.userapi.service.push.PushTransport;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RuStore push, universal API: {@code POST /v1/send} on {@code vkpns-universal.rustore.ru}, with the
 * project and the service key inside the body rather than in a header.
 *
 * <p>RuStore has two sending APIs, and the one to use is decided by the SDK in the application, not
 * by preference. The application is built on {@code ru.rustore.sdk:universalpush}, and that SDK
 * passes a message up only when its own service key is present in {@code data} — a key the universal
 * sending service adds by itself. Sent through the native endpoint
 * ({@code /v1/projects/{projectId}/messages:send}), everything answers success, the device does
 * receive the message, and the application drops it without a word. That is the failure this
 * addresses; adding the key by hand instead is not an option — it is undocumented SDK internals and
 * would break exactly as quietly on the next version.
 *
 * <p>Neither a collapse key nor a priority is sent: their field names are not confirmed against the
 * documentation, and inventing them would produce a request that looks right and silently drops the
 * fields. The user-visible collapsing is the client's anyway — it rewrites the notification of a
 * conversation it already shows.
 */
@Slf4j
@Component
public class RuStorePushTransport implements PushTransport {

    private static final String BASE_URL = "https://vkpns-universal.rustore.ru";
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /** How much of a rejected token the answer names — the whole of what there is to match on. */
    private static final int REJECTED_TOKEN_PREFIX_LENGTH = 6;

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
                    .uri("/v1/send")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body(token, message))
                    .retrieve()
                    .toBodilessEntity();
            return PushDelivery.DELIVERED;
        } catch (RestClientResponseException e) {
            PushDelivery delivery = deliveryFor(e.getResponseBodyAsString(), token);
            // The status and the verdict only: RestClient puts the URL into the message, and the
            // body of the request next to it carries the service key.
            log.debug("RuStore push refused with {} ({})", e.getStatusCode(), delivery);
            return delivery;
        } catch (Exception e) {
            log.warn("RuStore push failed: {}", e.getClass().getSimpleName());
            return PushDelivery.FAILED;
        }
    }

    /**
     * The universal API answers for the request as a whole — there is no per-token result — and names
     * the tokens it rejected by their first characters inside {@code errors}. So a subscription is
     * dropped only when its own token is named there; everything else, a malformed request included,
     * is a failure that changes nothing. The status code cannot decide this on its own any more: the
     * same 400 covers «this token is not a token» and «this request is wrong», and dropping on the
     * latter would delete the devices of everyone the request was for.
     */
    static PushDelivery deliveryFor(String responseBody, String token) {
        String prefix = rejectedPrefix(token);
        if (prefix.isEmpty()) {
            return PushDelivery.FAILED;
        }

        Object errors = JsonUtils.fromJsonToMap(responseBody).get("errors");
        if (errors instanceof List<?> reported) {
            for (Object error : reported) {
                if (String.valueOf(error).contains(prefix)) {
                    return PushDelivery.TOKEN_GONE;
                }
            }
        }
        return PushDelivery.FAILED;
    }

    private static String rejectedPrefix(String token) {
        if (token == null) {
            return "";
        }
        return token.length() <= REJECTED_TOKEN_PREFIX_LENGTH
                ? token
                : token.substring(0, REJECTED_TOKEN_PREFIX_LENGTH);
    }

    /**
     * {@code {"providers": {"rustore": {…}}, "tokens": {"rustore": [token]}, "message": {…}}} — data
     * only, which the API allows explicitly when {@code message.data} is not empty. The notification
     * is drawn by the application itself, so that it can stay silent while the person is reading that
     * very conversation. The whole request is capped at 4 KB, which the preview length keeps us well
     * under.
     */
    Map<String, Object> body(String token, PushMessage message) {
        PushProperties.RuStore rustore = pushProperties.getRustore();
        Duration ttl = message.ttl() != null ? message.ttl() : pushProperties.getTtl();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("data", message.data());
        payload.put("android", Map.of("ttl", ttl.toSeconds() + "s"));

        Map<String, Object> body = new LinkedHashMap<>();
        // The credentials travel in the body here — this API has no Authorization header at all.
        body.put("providers", Map.of("rustore", Map.of(
                "project_id", rustore.getProjectId(),
                "auth_token", rustore.getServiceKey())));
        // One request per device today; the field is a list because the API takes several at once,
        // and batching a person's devices would only change how the answer is read.
        body.put("tokens", Map.of("rustore", List.of(token)));
        body.put("message", payload);
        return body;
    }
}
