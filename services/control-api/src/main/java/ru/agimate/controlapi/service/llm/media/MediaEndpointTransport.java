package ru.agimate.controlapi.service.llm.media;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.AttributionHeaders;
import ru.agimate.controlapi.database.enums.MediaTransportType;
import ru.agimate.controlapi.service.llm.ExtraBodyMerge;
import ru.agimate.controlapi.service.llm.LlmCredentialsResolver.ResolvedLlm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The media-endpoint dialect: {@code POST /media} with a model-native {@code input}
 * ({@code prompt}, {@code images[]}) and the result as a link on the provider's storage. Implemented
 * against Polza, whose image models are unreachable over chat/completions at all — an image-only
 * model answers a bare 500 there, an image+text one answers 200 with no picture and a full charge.
 *
 * <p>{@code async: false} asks for the answer in the same request, and that is the normal path. A
 * provider is still free to hand back a pending job (a slow model, a server-side cap), so a bounded
 * poll follows — otherwise exactly the heaviest generations would be the ones that fail.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MediaEndpointTransport implements MediaTransport {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // Below the worker's budget for generation tools (30 min), as in MediaInferenceHttp.
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(25);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    private static final Duration POLL_BUDGET = Duration.ofMinutes(20);
    private static final int ERROR_BODY_PREVIEW = 300;
    private static final String STATUS_COMPLETED = "completed";
    /** Filled from the call, never from the model's declaration. */
    private static final Set<String> CALLER_OWNED_PARAMS = Set.of("prompt", "images");

    private final AttributionHeaders attribution;
    private final RemoteImageFetcher imageFetcher;

    @Override
    public MediaTransportType type() {
        return MediaTransportType.MEDIA_ENDPOINT;
    }

    @Override
    public GeneratedImage generate(GenerationRequest request) {
        ResolvedLlm resolved = request.resolved();
        Map<String, Object> response = post(resolved, body(request));
        Map<String, Object> completed = awaitCompletion(resolved, response);
        InputImage image = imageFetcher.fetch(resultUrl(completed));
        logCost(resolved, completed);
        // No token counts here: media endpoints bill in money, and inventing tokens would corrupt the log.
        return new GeneratedImage(image.bytes(), image.mime(), "", null);
    }

    /**
     * {@code input} carries the model-native parameters, and sources go in as bare base64 (verified
     * against Polza: {@code {"type":"base64","data":"<payload>"}}, no data-URI prefix).
     *
     * <p>Three layers, each beating the one before it: the parameters declared by the model, then the
     * provider's and model's extra_body, then the call itself (model, prompt, sources). So a value
     * configured by hand always wins over the listing's default, and the prompt can never be
     * overwritten by either.
     */
    static Map<String, Object> body(GenerationRequest request) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prompt", request.prompt());
        if (!request.sources().isEmpty()) {
            List<Map<String, Object>> images = new ArrayList<>();
            for (InputImage source : request.sources()) {
                images.add(Map.of("type", "base64",
                        "data", Base64.getEncoder().encodeToString(source.bytes())));
            }
            input.put("images", images);
        }
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("model", request.resolved().model());
        core.put("async", false);
        core.put("input", input);

        Map<String, Object> defaults = Map.of("input", declaredDefaults(request.resolved()));
        return ExtraBodyMerge.merge(
                ExtraBodyMerge.merge(defaults, request.resolved().extraBody()), core);
    }

    /**
     * Values for the parameters the model declares in the listing ({@code top_provider.parameters}),
     * taken from the declaration itself: {@code default} when there is one, otherwise the first
     * allowed value.
     *
     * <p>Everything declared is filled, not only what is flagged {@code required} — the flags are not
     * to be trusted. Measured: {@code google/gemini-3.1-flash-lite-image} marks nothing as required
     * and still answers {@code 400 "This field is required"} without {@code aspect_ratio}, and the
     * message does not even name the field. Filling a parameter the model would have defaulted anyway
     * costs nothing; missing one costs a failed tool call.
     *
     * <p>{@code prompt} and {@code images} are never touched — they come from the call. Parameters
     * declared without {@code default}/{@code values} (a free number such as {@code seed} or
     * {@code upscale_factor}) are skipped: there is nothing there to pick, and inventing a value would
     * be a guess about someone's picture.
     */
    static Map<String, Object> declaredDefaults(ResolvedLlm resolved) {
        Map<?, ?> parameters = declaredParameters(resolved.modelMetadata());
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : parameters.entrySet()) {
            if (!(entry.getKey() instanceof String name) || CALLER_OWNED_PARAMS.contains(name)
                    || !(entry.getValue() instanceof Map<?, ?> spec)) {
                continue;
            }
            declaredValue(spec).ifPresent(value -> defaults.put(name, value));
        }
        return defaults;
    }

    private static Map<?, ?> declaredParameters(Map<String, Object> metadata) {
        return metadata.get("top_provider") instanceof Map<?, ?> topProvider
                && topProvider.get("parameters") instanceof Map<?, ?> parameters
                ? parameters : Map.of();
    }

    private static Optional<Object> declaredValue(Map<?, ?> spec) {
        if (spec.get("default") != null) {
            return Optional.of(spec.get("default"));
        }
        if (spec.get("values") instanceof List<?> values && !values.isEmpty()) {
            return Optional.ofNullable(values.get(0));
        }
        return Optional.empty();
    }

    private Map<String, Object> post(ResolvedLlm resolved, Map<String, Object> body) {
        String baseUrl = MediaInferenceHttp.resolveBaseUrl(resolved.provider());
        String json = JsonUtils.writeValueAsString(body);
        try {
            return client(baseUrl).post()
                    .uri("/media")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + resolved.apiKey())
                    .headers(h -> attribution.llmHeaders(baseUrl).forEach(h::set))
                    .body(json)
                    .exchange((request, response) -> readResponse(response));
        } catch (ResourceAccessException e) {
            throw new MediaInferenceException("media generation request failed: " + e.getMessage());
        }
    }

    /**
     * The generation's terminal state. A job that is still running is polled by id; the budget is below
     * the HTTP read timeout, so a provider that never finishes fails as a clean error rather than as a
     * worker giving up.
     */
    private Map<String, Object> awaitCompletion(ResolvedLlm resolved, Map<String, Object> response) {
        Map<String, Object> current = response;
        long deadline = System.nanoTime() + POLL_BUDGET.toNanos();
        while (!STATUS_COMPLETED.equals(current.get("status"))) {
            requireNotFailed(current);
            if (System.nanoTime() > deadline) {
                throw new MediaInferenceException("media generation did not finish within "
                        + POLL_BUDGET.toMinutes() + " minutes");
            }
            sleep();
            current = poll(resolved, id(current));
        }
        return current;
    }

    static void requireNotFailed(Map<String, Object> response) {
        Object status = response.get("status");
        if (status == null) {
            throw new MediaInferenceException("media generation response carried no status");
        }
        if ("failed".equals(status) || "cancelled".equals(status) || "canceled".equals(status)) {
            throw new MediaInferenceException("media generation " + status
                    + (response.get("error") == null ? "" : ": " + response.get("error")));
        }
    }

    private Map<String, Object> poll(ResolvedLlm resolved, String id) {
        String baseUrl = MediaInferenceHttp.resolveBaseUrl(resolved.provider());
        try {
            return client(baseUrl).get()
                    .uri("/media/{id}", id)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + resolved.apiKey())
                    .exchange((request, response) -> readResponse(response));
        } catch (ResourceAccessException e) {
            throw new MediaInferenceException("media generation status request failed: " + e.getMessage());
        }
    }

    /**
     * The id to poll by. The submit answer names it {@code requestId} and the status answer {@code id};
     * both are accepted so the first poll after a submit works.
     */
    static String id(Map<String, Object> response) {
        Object id = response.get("requestId") != null ? response.get("requestId") : response.get("id");
        if (!(id instanceof String value) || value.isBlank()) {
            throw new MediaInferenceException("media generation is pending but carries no id to poll");
        }
        return value;
    }

    /** {@code data[0].url} — the link to the generated picture. */
    static String resultUrl(Map<String, Object> response) {
        if (response.get("data") instanceof List<?> data && !data.isEmpty()
                && data.get(0) instanceof Map<?, ?> first && first.get("url") instanceof String url
                && !url.isBlank()) {
            return url;
        }
        throw new MediaInferenceException("media generation completed without an image link");
    }

    /**
     * The provider's own price of the call. It is not written to {@code llm_usage_log} — that log
     * counts tokens, and money has no column there yet — so the fact is at least visible in the log
     * until the accounting learns about cost.
     */
    private static void logCost(ResolvedLlm resolved, Map<String, Object> response) {
        if (response.get("usage") instanceof Map<?, ?> usage && usage.get("cost_rub") != null) {
            log.info("media generation model={} provider={} cost_rub={}",
                    resolved.model(), resolved.provider().getId(), usage.get("cost_rub"));
        }
    }

    private static Map<String, Object> readResponse(ClientHttpResponse response) throws IOException {
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("media endpoint returned {}: {}", response.getStatusCode(), truncate(body));
            throw new MediaInferenceException("media provider rejected the generation ("
                    + response.getStatusCode().value() + "): " + truncate(body));
        }
        try {
            return JsonUtils.fromJsonToMap(body);
        } catch (Exception e) {
            throw new MediaInferenceException("media provider returned an unparsable response");
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MediaInferenceException("media generation wait was interrupted");
        }
    }

    private static RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    private static String truncate(String body) {
        return body.length() <= ERROR_BODY_PREVIEW ? body : body.substring(0, ERROR_BODY_PREVIEW) + "…";
    }
}
