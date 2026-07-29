package ru.agimate.controlapi.service.llm.media;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.AttributionHeaders;
import ru.agimate.controlapi.database.entities.LlmProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The HTTP transport of media inference: {@code POST /chat/completions} of an OpenAI-compatible
 * provider (the only path in phase 1 — see docs/connectors/media.md) plus parsing of the multimodal
 * response (images in {@code message.images[]} as data URIs, the OpenRouter convention). Modelled on
 * {@link LlmDiscoveryHttp}, but with a long read timeout: generation can take minutes.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MediaInferenceHttp {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // Below the worker's budget for generation tools (30 min): a provider hang turns into a clean control-api
    // error before the worker gives up waiting.
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(25);
    private static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
    private static final int ERROR_BODY_PREVIEW = 300;

    private final AttributionHeaders attribution;

    /**
     * One chat/completions call; the body is already assembled by the caller (model/messages/extra_body).
     * The key travels in the header alone and never reaches the logs or exceptions.
     *
     * @return the parsed JSON response from the provider
     * @throws MediaInferenceException a non-2xx, a network failure or timeout, or an unparseable response
     */
    public Map<String, Object> chatCompletions(LlmProvider provider, String apiKey, Map<String, Object> body) {
        String baseUrl = resolveBaseUrl(provider);
        String json = JsonUtils.writeValueAsString(body);
        try {
            return client(baseUrl).post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .headers(h -> attribution.llmHeaders(baseUrl).forEach(h::set))
                    .body(json)
                    .exchange((request, response) -> readResponse(response));
        } catch (ResourceAccessException e) {
            throw new MediaInferenceException("media model request failed: " + e.getMessage());
        }
    }

    private static Map<String, Object> readResponse(ClientHttpResponse response) throws IOException {
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("media chat/completions returned {}: {}", response.getStatusCode(), truncate(body));
            throw new MediaInferenceException(rejectionMessage(response.getStatusCode(), body));
        }
        try {
            return JsonUtils.fromJsonToMap(body);
        } catch (Exception e) {
            throw new MediaInferenceException("media model returned unparsable response");
        }
    }

    /**
     * The text the agent will read verbatim. A 5xx gets the transport hint: a gateway that serves
     * images on a separate endpoint fails exactly like this (Polza answers a bare «internal error»),
     * and without the hint the agent sees an outage and retries a call that can never succeed. The
     * provider's own body is kept either way — for a real outage it is the only useful part.
     */
    static String rejectionMessage(HttpStatusCode status, String body) {
        if (status.is5xxServerError()) {
            return "media provider returned a server error (" + status.value()
                    + "). Image generation is supported only over chat/completions with modalities;"
                    + " a provider that serves images on a separate endpoint will always fail here."
                    + " Provider said: " + truncate(body);
        }
        return "media model rejected request (" + status.value() + "): " + truncate(body);
    }

    /** Shared with the other transports: which URL this provider type is addressed at. */
    static String resolveBaseUrl(LlmProvider provider) {
        String configured = provider.getBaseUrl();
        return switch (provider.getProviderType()) {
            case OPENAI -> stripTrailingSlash(
                    configured == null || configured.isBlank() ? OPENAI_BASE_URL : configured);
            case OPENAI_COMPATIBLE -> {
                if (configured == null || configured.isBlank()) {
                    throw new MediaInferenceException("provider has no base_url configured");
                }
                yield stripTrailingSlash(configured);
            }
            default -> throw new MediaInferenceException("provider type " + provider.getProviderType()
                    + " is not supported for media tools yet (OpenAI-compatible only)");
        };
    }

    private static RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String truncate(String body) {
        return body.length() <= ERROR_BODY_PREVIEW ? body : body.substring(0, ERROR_BODY_PREVIEW) + "…";
    }

    // ---- response parsing (static, testable without HTTP) ----------------------------------

    /** Contents of a data URI: {@code data:<mime>;base64,<payload>}. */
    public record DataUri(String mime, byte[] bytes) {
    }

    /** Tokens from the response's {@code usage}; {@code cacheReadTokens} is null when absent or zero. */
    public record Usage(int inputTokens, int outputTokens, Integer cacheReadTokens) {
    }

    /**
     * The first generated image: {@code choices[0].message.images[*].image_url.url} carrying a data URI
     * (the OpenRouter format). A response with no image (a textual refusal by the model) → empty.
     */
    public static Optional<DataUri> firstImage(Map<String, Object> response) {
        if (!(message(response).get("images") instanceof List<?> images)) {
            return Optional.empty();
        }
        return images.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(image -> image.get("image_url") instanceof Map<?, ?> u ? u.get("url") : null)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(MediaInferenceHttp::parseDataUri)
                .flatMap(Optional::stream)
                .findFirst();
    }

    /**
     * The answer's text: {@code choices[0].message.content} — either a string or an array of parts
     * (concatenating those with {@code type=text}). No text → an empty string.
     */
    public static String messageText(Map<String, Object> response) {
        Object content = message(response).get("content");
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> p && "text".equals(p.get("type"))
                        && p.get("text") instanceof String s) {
                    sb.append(s);
                }
            }
            return sb.toString();
        }
        return "";
    }

    /** The response's {@code usage}; absent → null (the caller writes zeroes with a warning). */
    public static Usage usage(Map<String, Object> response) {
        if (!(response.get("usage") instanceof Map<?, ?> usage)) {
            return null;
        }
        Integer cached = null;
        if (usage.get("prompt_tokens_details") instanceof Map<?, ?> details
                && details.get("cached_tokens") instanceof Number n && n.intValue() > 0) {
            cached = n.intValue();
        }
        return new Usage(intValue(usage.get("prompt_tokens")), intValue(usage.get("completion_tokens")), cached);
    }

    static Optional<DataUri> parseDataUri(String url) {
        if (url == null || !url.startsWith("data:")) {
            return Optional.empty();
        }
        int semi = url.indexOf(";base64,");
        if (semi <= "data:".length()) {
            return Optional.empty();
        }
        String mime = url.substring("data:".length(), semi);
        try {
            byte[] bytes = Base64.getDecoder().decode(url.substring(semi + ";base64,".length()));
            return bytes.length == 0 ? Optional.empty() : Optional.of(new DataUri(mime, bytes));
        } catch (IllegalArgumentException e) {
            log.warn("media response carried a malformed base64 data-URI (mime {})", mime);
            return Optional.empty();
        }
    }

    private static Map<?, ?> message(Map<String, Object> response) {
        if (response.get("choices") instanceof List<?> choices && !choices.isEmpty()
                && choices.get(0) instanceof Map<?, ?> choice
                && choice.get("message") instanceof Map<?, ?> message) {
            return message;
        }
        return Map.of();
    }

    private static int intValue(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
