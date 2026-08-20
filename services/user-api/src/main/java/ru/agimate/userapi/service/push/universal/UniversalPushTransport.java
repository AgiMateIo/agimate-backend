package ru.agimate.userapi.service.push.universal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.userapi.config.PushProperties;
import ru.agimate.userapi.service.push.PushDelivery;
import ru.agimate.userapi.service.push.PushMessage;
import ru.agimate.userapi.service.push.PushTokens;
import ru.agimate.userapi.service.push.PushTransport;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The universal sending service, {@code POST /v1/send} on {@code vkpns-universal.rustore.ru}: the
 * road to every channel the device has, RuStore's own and FCM alike, with the project and the
 * credentials inside the body rather than in a header.
 *
 * <p><b>Why not the native API of each vendor.</b> The application is built on the universal SDK,
 * and that SDK passes a message up only when the service key of this very service is present in
 * {@code data} — a key it adds by itself. Sent natively (RuStore's
 * {@code /v1/projects/{projectId}/messages:send}, or Firebase's {@code fcm.googleapis.com}),
 * everything answers success, the device does receive the message, and the application drops it
 * without a word. Adding the key by hand instead is not an option: it is undocumented SDK internals
 * and would break exactly as quietly on the next version.
 *
 * <p>So two channels differ in two values only — the vendor's name in the body and where the
 * credentials come from. The endpoint, the shape of the request and the way a refusal reads are
 * shared, and live here.
 *
 * <p>Neither a collapse key nor a priority is sent: their field names are not confirmed against the
 * documentation, and inventing them would produce a request that looks right and silently drops the
 * fields. The user-visible collapsing is the client's anyway — it rewrites the notification of a
 * conversation it already shows.
 */
@Slf4j
public abstract class UniversalPushTransport implements PushTransport {

    private static final String BASE_URL = "https://vkpns-universal.rustore.ru";
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /** How much of a rejected token the answer names — the whole of what there is to match on. */
    private static final int REJECTED_TOKEN_PREFIX_LENGTH = 6;

    private static final int MAX_LOGGED_ANSWER = 500;

    /**
     * What a refusal says when it is the token that is wrong, and not us. Lowercase, matched as
     * substrings, so the singular covers the plural the API actually writes («invalid tokens»).
     */
    private static final List<String> TOKEN_ERROR_MARKERS = List.of("invalid token", "unregistered", "not_found");

    /**
     * One client for every channel: they share the endpoint, so a client apiece would only split
     * the connection pool to one host and start a second selector thread for nothing.
     *
     * <p>The factory is pinned rather than left to {@link RestClient}'s classpath detection, as in
     * {@code TelegramApiClient}: what that detection picks changes when a dependency changes, and a
     * push transport is a poor place to discover a new HTTP stack.
     */
    private static final RestClient REST_CLIENT = restClient();

    /** Read by the subclasses: their credentials live in it beside the shared ttl. */
    protected final PushProperties pushProperties;

    protected UniversalPushTransport(PushProperties pushProperties) {
        this.pushProperties = pushProperties;
    }

    private static RestClient restClient() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().baseUrl(BASE_URL).requestFactory(factory).build();
    }

    /**
     * The vendor's name for this channel in the request body. Not the name the token is stored
     * under: the SDK on the device and the sending API disagree about Firebase — {@code firebase}
     * there, {@code fcm} here — and that disagreement is the vendor's, not a typo.
     */
    protected abstract String wireName();

    /** The project the mobile application is built against, as this channel states it. */
    protected abstract String projectId();

    /**
     * The secret authorizing the send. Asked for on every send rather than held: for FCM it is an
     * access token that outlives neither the hour nor a deploy.
     */
    protected abstract String authToken();

    @Override
    public PushDelivery send(String token, PushMessage message) {
        // Asked for once per send and kept for the length of it: the refusal has to be stripped of
        // the very secret this request carried, and asking twice would mean two answers.
        String authToken = null;
        try {
            authToken = authToken();
            REST_CLIENT.post()
                    .uri("/v1/send")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body(token, message, authToken))
                    .retrieve()
                    .toBodilessEntity();
            return PushDelivery.DELIVERED;
        } catch (RestClientResponseException e) {
            String answer = e.getResponseBodyAsString();
            PushDelivery delivery = deliveryFor(answer, token);
            // The transport's own answer, and never the exception message: RestClient puts the URL
            // into it, and the request body beside it carries the credentials. Without the reason a
            // dropped subscription looks exactly like a project the stand is misconfigured for, and
            // both of them show up as silence on the phone.
            log.warn("{} push refused with {} ({}): {}", provider(), e.getStatusCode(), delivery,
                    refusal(answer, token, authToken));
            return delivery;
        } catch (Exception e) {
            log.warn("{} push failed: {}", provider(), e.getClass().getSimpleName());
            return PushDelivery.FAILED;
        }
    }

    /**
     * The universal API answers for the request as a whole — there is no per-token result — and names
     * the tokens it rejected by their first characters inside {@code errors}. So a subscription is
     * dropped only when its own token is named there <b>and</b> named as a bad token: the prefix
     * alone appears in any refusal that echoes the request back, a stale auth token and a wrong
     * project included, and each of those would take a live device down with it. The status code
     * cannot decide this on its own either: the same 400 covers «this token is not a token» and
     * «this request is wrong».
     *
     * <p>The asymmetry is deliberate. Keeping a dead subscription costs one request per
     * notification; dropping a live one costs that device a day of silence, because the application
     * holds its own record of a confirmed registration for 24 hours and will not re-register sooner.
     */
    static PushDelivery deliveryFor(String responseBody, String token) {
        String prefix = rejectedPrefix(token);
        if (prefix.isEmpty()) {
            return PushDelivery.FAILED;
        }

        Object errors = JsonUtils.fromJsonToMap(responseBody).get("errors");
        if (errors instanceof List<?> reported) {
            for (Object error : reported) {
                String reason = String.valueOf(error);
                if (reason.contains(prefix) && namesATokenError(reason)) {
                    return PushDelivery.TOKEN_GONE;
                }
            }
        }
        return PushDelivery.FAILED;
    }

    /**
     * The answer as the transport wrote it, minus the two secrets the request carried. The rejected
     * tokens come back as prefixes, but nothing promises the message will not quote one whole, and
     * a token in a log line is the right to notify that device. The credential matters more: an
     * answer that echoes the request would put the channel's {@code auth_token} into the log — the
     * right to notify <b>every</b> device of this installation, and for RuStore a key that does not
     * expire.
     *
     * @param authToken the secret this request went out with; null when the send failed before it
     *                  was even fetched
     */
    static String refusal(String responseBody, String token, String authToken) {
        if (responseBody == null || responseBody.isBlank()) {
            return "<no body>";
        }
        String answer = without(responseBody, token, PushTokens.masked(token));
        answer = without(answer, authToken, "<credentials>");

        return answer.length() <= MAX_LOGGED_ANSWER ? answer : answer.substring(0, MAX_LOGGED_ANSWER) + "…";
    }

    private static String without(String text, String secret, String replacement) {
        return secret == null || secret.isEmpty() ? text : text.replace(secret, replacement);
    }

    private static boolean namesATokenError(String reason) {
        String lowered = reason.toLowerCase(Locale.ROOT);
        return TOKEN_ERROR_MARKERS.stream().anyMatch(lowered::contains);
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
     * {@code {"providers": {"<wire>": {…}}, "tokens": {"<wire>": [token]}, "message": {…}}} — data
     * only, which the API allows explicitly when {@code message.data} is not empty. The notification
     * is drawn by the application itself, so that it can stay silent while the person is reading that
     * very conversation. The whole request is capped at 4 KB, which the preview length keeps us well
     * under.
     */
    Map<String, Object> body(String token, PushMessage message, String authToken) {
        Duration ttl = message.ttl() != null ? message.ttl() : pushProperties.getTtl();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("data", message.data());
        payload.put("android", Map.of("ttl", ttl.toSeconds() + "s"));

        Map<String, Object> body = new LinkedHashMap<>();
        // The credentials travel in the body here — this API has no Authorization header at all.
        body.put("providers", Map.of(wireName(), Map.of(
                "project_id", projectId(),
                "auth_token", authToken)));
        // One token per request, and that is what makes the answer readable: it is aggregated, and
        // with several devices in it there is nothing to attribute a rejected prefix to.
        body.put("tokens", Map.of(wireName(), List.of(token)));
        body.put("message", payload);
        return body;
    }
}
