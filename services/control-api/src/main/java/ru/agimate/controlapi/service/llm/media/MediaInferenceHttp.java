package ru.agimate.controlapi.service.llm.media;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.service.llm.ChatCompletionsHttp;
import ru.agimate.controlapi.service.llm.ChatCompletionsHttp.ChatCompletionsException;
import ru.agimate.controlapi.service.llm.ChatCompletionsHttp.Usage;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The HTTP side of media inference: {@code POST /chat/completions} through {@link ChatCompletionsHttp}
 * (the only path in phase 1 — see docs/connectors/media.md) with a long read timeout, because
 * generation can take minutes, plus parsing of the multimodal response (images in
 * {@code message.images[]} as data URIs, the OpenRouter convention). A failure becomes a
 * {@link MediaInferenceException} with the text the agent reads.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MediaInferenceHttp {

    // Below the worker's budget for generation tools (30 min): a provider hang turns into a clean control-api
    // error before the worker gives up waiting.
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(25);

    private final ChatCompletionsHttp http;

    /**
     * One chat/completions call; the body is already assembled by the caller (model/messages/extra_body).
     *
     * @return the parsed JSON response from the provider
     * @throws MediaInferenceException a non-2xx, a network failure or timeout, or an unparseable response
     */
    public Map<String, Object> chatCompletions(LlmProvider provider, String apiKey, Map<String, Object> body) {
        try {
            return http.post(provider, apiKey, body, READ_TIMEOUT);
        } catch (ChatCompletionsException e) {
            throw asMediaFailure(e);
        }
    }

    private static MediaInferenceException asMediaFailure(ChatCompletionsException e) {
        return e.getStatus() > 0
                ? new MediaInferenceException(rejectionMessage(HttpStatusCode.valueOf(e.getStatus()), e.getBody()))
                : new MediaInferenceException("media " + e.getMessage());
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
                    + " Provider said: " + body;
        }
        return "media model rejected request (" + status.value() + "): " + body;
    }

    /** Shared with the other transports: which URL this provider type is addressed at. */
    static String resolveBaseUrl(LlmProvider provider) {
        try {
            return ChatCompletionsHttp.resolveBaseUrl(provider);
        } catch (ChatCompletionsException e) {
            throw asMediaFailure(e);
        }
    }

    // ---- response parsing (static, testable without HTTP) ----------------------------------

    /** Contents of a data URI: {@code data:<mime>;base64,<payload>}. */
    public record DataUri(String mime, byte[] bytes) {
    }

    /**
     * The first generated image: {@code choices[0].message.images[*].image_url.url} carrying a data URI
     * (the OpenRouter format). A response with no image (a textual refusal by the model) → empty.
     */
    public static Optional<DataUri> firstImage(Map<String, Object> response) {
        if (!(ChatCompletionsHttp.message(response).get("images") instanceof List<?> images)) {
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

    /** See {@link ChatCompletionsHttp#messageText}. */
    public static String messageText(Map<String, Object> response) {
        return ChatCompletionsHttp.messageText(response);
    }

    /** See {@link ChatCompletionsHttp#usage}. */
    public static Usage usage(Map<String, Object> response) {
        return ChatCompletionsHttp.usage(response);
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
}
