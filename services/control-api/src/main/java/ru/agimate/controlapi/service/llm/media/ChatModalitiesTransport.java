package ru.agimate.controlapi.service.llm.media;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.enums.MediaTransportType;
import ru.agimate.controlapi.service.llm.ExtraBodyMerge;
import ru.agimate.controlapi.service.llm.LlmCredentialsResolver.ResolvedLlm;
import ru.agimate.controlapi.service.llm.media.MediaInferenceHttp.DataUri;
import ru.agimate.controlapi.service.llm.media.MediaInferenceHttp.Usage;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The OpenRouter dialect: {@code POST /chat/completions} with {@code modalities: ["image","text"]},
 * sources as parts of the same user message, the picture back in {@code message.images[]} as a data
 * URI. The default transport — this is the convention the media connector started from.
 */
@Component
@RequiredArgsConstructor
public class ChatModalitiesTransport implements MediaTransport {

    private final MediaInferenceHttp http;

    @Override
    public MediaTransportType type() {
        return MediaTransportType.CHAT_MODALITIES;
    }

    @Override
    public GeneratedImage generate(GenerationRequest request) {
        ResolvedLlm resolved = request.resolved();
        Object content = request.sources().isEmpty()
                ? request.prompt()
                : contentParts(request.prompt(), request.sources());
        Map<String, Object> body = requestBody(resolved, Map.of(
                "modalities", List.of("image", "text"),
                "messages", List.of(Map.of("role", "user", "content", content))));

        Map<String, Object> response = http.chatCompletions(resolved.provider(), resolved.apiKey(), body);

        Usage usage = MediaInferenceHttp.usage(response);
        String text = MediaInferenceHttp.messageText(response);
        Optional<DataUri> image = MediaInferenceHttp.firstImage(response);
        return image
                .map(uri -> new GeneratedImage(uri.bytes(), uri.mime(), text, usage))
                .orElseGet(() -> GeneratedImage.refusal(text, usage));
    }

    /** The final request body: the resolution's extra_body underneath, with the core (model/messages) winning. */
    private static Map<String, Object> requestBody(ResolvedLlm resolved, Map<String, Object> core) {
        Map<String, Object> withModel = new LinkedHashMap<>(core);
        withModel.put("model", resolved.model());
        return ExtraBodyMerge.merge(resolved.extraBody(), withModel);
    }

    /**
     * The prompt plus the pictures as parts of a single user message, in list order: numbering them in
     * the prompt («image 1», «image 2») relies on that order.
     */
    private static List<Map<String, Object>> contentParts(String prompt, List<InputImage> sources) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("type", "text", "text", prompt));
        for (InputImage image : sources) {
            parts.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUri(image))));
        }
        return parts;
    }

    static String dataUri(InputImage image) {
        return "data:" + image.mime() + ";base64," + Base64.getEncoder().encodeToString(image.bytes());
    }
}
