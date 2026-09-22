package ru.agimate.controlapi.service.llm;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.AttributionHeaders;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.service.http.PublicOnlyHttp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * One {@code POST /chat/completions} of an OpenAI-compatible provider, made by control-api itself —
 * the calls that are not the agent loop: media inference and the platform's chores. The agent loop
 * lives in the worker and never comes through here. The key travels in the header alone and never
 * reaches the logs or exceptions.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatCompletionsHttp {

    private static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
    private static final int ERROR_BODY_PREVIEW = 300;

    private final AttributionHeaders attribution;
    private final PublicOnlyHttp http;

    /** Tokens from the response's {@code usage}; {@code cacheReadTokens} is null when absent or zero. */
    public record Usage(int inputTokens, int outputTokens, Integer cacheReadTokens) {
    }

    /**
     * The call did not produce a usable answer. {@code status} is the provider's HTTP status, or 0 when
     * there was no answer at all (network, timeout, a provider this path cannot address); {@code body}
     * is the provider's own words, already cut to a preview.
     */
    @Getter
    public static class ChatCompletionsException extends RuntimeException {

        private final int status;
        private final String body;

        public ChatCompletionsException(int status, String message, String body) {
            super(message);
            this.status = status;
            this.body = body;
        }
    }

    /** The body is already assembled by the caller (model/messages/extra_body). */
    public Map<String, Object> post(LlmProvider provider, String apiKey, Map<String, Object> body,
                                    Duration readTimeout) {
        String baseUrl = resolveBaseUrl(provider);
        String json = JsonUtils.writeValueAsString(body);
        try {
            return http.restClient(readTimeout).baseUrl(baseUrl).build().post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .headers(h -> attribution.llmHeaders(baseUrl).forEach(h::set))
                    .body(json)
                    .exchange((request, response) -> readResponse(response));
        } catch (ResourceAccessException e) {
            throw new ChatCompletionsException(0, "model request failed: " + e.getMessage(), null);
        }
    }

    private static Map<String, Object> readResponse(ClientHttpResponse response) throws IOException {
        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        int status = response.getStatusCode().value();
        if (!response.getStatusCode().is2xxSuccessful()) {
            log.warn("chat/completions returned {}: {}", status, truncate(body));
            throw new ChatCompletionsException(status,
                    "model rejected request (" + status + "): " + truncate(body), truncate(body));
        }
        try {
            return JsonUtils.fromJsonToMap(body);
        } catch (Exception e) {
            throw new ChatCompletionsException(0, "model returned unparsable response", null);
        }
    }

    /** Which URL this provider type is addressed at. */
    public static String resolveBaseUrl(LlmProvider provider) {
        String configured = provider.getBaseUrl();
        return switch (provider.getProviderType()) {
            case OPENAI -> stripTrailingSlash(
                    configured == null || configured.isBlank() ? OPENAI_BASE_URL : configured);
            case OPENAI_COMPATIBLE -> {
                if (configured == null || configured.isBlank()) {
                    throw new ChatCompletionsException(0, "provider has no base_url configured", null);
                }
                yield stripTrailingSlash(configured);
            }
            default -> throw new ChatCompletionsException(0, "provider type " + provider.getProviderType()
                    + " is not supported here yet (OpenAI-compatible only)", null);
        };
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

    /** {@code choices[0].message}, or an empty map for a response of any other shape. */
    public static Map<?, ?> message(Map<String, Object> response) {
        if (response.get("choices") instanceof List<?> choices && !choices.isEmpty()
                && choices.get(0) instanceof Map<?, ?> choice
                && choice.get("message") instanceof Map<?, ?> message) {
            return message;
        }
        return Map.of();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String truncate(String body) {
        return body.length() <= ERROR_BODY_PREVIEW ? body : body.substring(0, ERROR_BODY_PREVIEW) + "…";
    }

    private static int intValue(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
