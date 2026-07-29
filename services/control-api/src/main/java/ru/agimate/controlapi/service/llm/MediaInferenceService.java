package ru.agimate.controlapi.service.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.service.LlmUsageService;
import ru.agimate.controlapi.service.llm.LlmCredentialsResolver.ResolvedLlm;
import ru.agimate.controlapi.service.llm.MediaInferenceHttp.DataUri;
import ru.agimate.controlapi.service.llm.MediaInferenceHttp.Usage;
import ru.agimate.controlapi.storage.FileStorageService;
import ru.agimate.controlapi.storage.FileStorageService.FileContent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The gate of «model as a tool» media inference (docs/connectors/media.md): resolving the model by
 * purpose ({@link LlmCredentialsResolver#resolveForCapability}) → chat/completions → bytes into the
 * file layer → usage accounting. The only point where the media connector touches the LLM
 * infrastructure: the connector itself knows nothing about providers, keys or the registry.
 *
 * <p>The key lives only within the call's frame (as in {@code GetLlmCredentials} — inline, never
 * persisted). What leaves are domain exceptions ({@link MediaInferenceException},
 * {@link NoCapableModelException}, {@link QuotaExceededException}, …); mapping them into a
 * {@code ConnectorException} is the connector's job.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MediaInferenceService {

    /** Prefix of {@code call_id} in llm_usage_log: a namespace apart from the {@code llm_call} worker. */
    static final String USAGE_CALL_PREFIX = "media:";
    /** Ceiling on an input picture: a data URI is buffered in the heap in full. */
    private static final long MAX_INPUT_IMAGE_BYTES = 20L * 1024 * 1024;
    /** Ceiling per call: with several inputs every picture sits in the heap at once, and calls run in parallel. */
    private static final long MAX_INPUT_TOTAL_BYTES = 25L * 1024 * 1024;
    /** No known image model handles more than 4 references — we cut it off before the HTTP call. */
    private static final int MAX_INPUT_IMAGES = 4;
    private static final String DEFAULT_DESCRIBE_PROMPT = "Describe this image in detail.";
    /** The modality value in the registry ({@code architecture.input/output_modalities} of /models). */
    private static final String IMAGE_MODALITY = "image";

    private final LlmCredentialsResolver credentialsResolver;
    private final MediaInferenceHttp http;
    private final FileStorageService fileStorageService;
    private final LlmUsageService llmUsageService;

    /**
     * Identity of a call: the owner of the files and quotas + the initiating agent + the initiating run
     * (to attribute usage to a run; {@code null} outside a run) + an idempotent call id.
     */
    public record MediaCall(UUID userId, UUID agentId, UUID runId, String callId) {
    }

    /**
     * Result of a generation. {@code file == null} means the model answered with text and no picture
     * (a safety refusal and the like); {@code text} is handed to the agent as-is — that is a result,
     * not an error.
     */
    public record ImageResult(StoredFile file, String text) {
    }

    /**
     * Generation from a prompt: with no sources — from scratch, with one — editing, with several —
     * composition (the model receives them in list order). The result is a file in storage with the
     * provenance {@code media:<model>} and the default TTL.
     */
    public ImageResult generateImage(MediaCall call, String prompt, List<String> sourceFileIds) {
        List<String> sources = sourceFileIds == null ? List.of() : sourceFileIds;
        ResolvedLlm resolved = credentialsResolver.resolveForCapability(
                call.agentId(), call.userId(), LlmPurpose.IMAGE);
        requireImageModality(resolved, LlmPurpose.IMAGE);
        Object content = sources.isEmpty() ? prompt : contentParts(call, prompt, sources);
        Map<String, Object> body = requestBody(resolved, Map.of(
                "modalities", List.of("image", "text"),
                "messages", List.of(Map.of("role", "user", "content", content))));

        Map<String, Object> response = http.chatCompletions(resolved.provider(), resolved.apiKey(), body);
        recordUsage(call, resolved, response);

        Optional<DataUri> image = MediaInferenceHttp.firstImage(response);
        String text = MediaInferenceHttp.messageText(response);
        if (image.isEmpty()) {
            log.info("media image call returned no image agent={} model={}", call.agentId(), resolved.model());
            return new ImageResult(null, text);
        }
        DataUri dataUri = image.get();
        StoredFile stored = fileStorageService.store(call.userId(), "media:" + resolved.model(),
                dataUri.mime(), dataUri.bytes().length, new ByteArrayInputStream(dataUri.bytes()), null);
        return new ImageResult(stored, text);
    }

    /** Vision over a file: a description of, or an answer about, the image {@code fileId}. */
    public String describeImage(MediaCall call, String fileId, String question) {
        ResolvedLlm resolved = credentialsResolver.resolveForCapability(
                call.agentId(), call.userId(), LlmPurpose.VISION);
        requireImageModality(resolved, LlmPurpose.VISION);
        String prompt = question == null || question.isBlank() ? DEFAULT_DESCRIBE_PROMPT : question;
        Map<String, Object> body = requestBody(resolved, Map.of(
                "messages", List.of(Map.of("role", "user", "content",
                        List.of(textPart(prompt), imagePart(loadImage(call, fileId).dataUri()))))));

        Map<String, Object> response = http.chatCompletions(resolved.provider(), resolved.apiKey(), body);
        recordUsage(call, resolved, response);
        return MediaInferenceHttp.messageText(response);
    }

    /**
     * The registry's verdict on «can this model do it at all», checked before HTTP: a model that
     * declares its modalities and has no {@code image} among them will never return a picture, and the
     * provider's own refusal for that case is unreadable (Polza answers a bare 500). A model the
     * registry knows nothing about passes — an empty list means «not declared», never «cannot», and
     * configuring a model before the first {@code refreshModels} is legitimate (see
     * {@link LlmCredentialsResolver}).
     */
    private static void requireImageModality(ResolvedLlm resolved, LlmPurpose purpose) {
        boolean generating = purpose == LlmPurpose.IMAGE;
        List<String> declared = generating ? resolved.outputModalities() : resolved.inputModalities();
        if (declared.isEmpty() || declared.contains(IMAGE_MODALITY)) {
            return;
        }
        throw new NoCapableModelException("Model '" + resolved.model() + "' of provider '"
                + resolved.provider().getName() + "' cannot " + (generating ? "generate" : "accept")
                + " images: it declares " + (generating ? "output" : "input") + " modalities "
                + declared + ". Bind a suitable model to this agent for " + purpose
                + " or fix the provider's " + purpose + " list.");
    }

    /** The final request body: the resolution's extra_body underneath, with the core (model/messages) winning. */
    private static Map<String, Object> requestBody(ResolvedLlm resolved, Map<String, Object> core) {
        Map<String, Object> withModel = new LinkedHashMap<>(core);
        withModel.put("model", resolved.model());
        return ExtraBodyMerge.merge(resolved.extraBody(), withModel);
    }

    /**
     * The prompt plus the pictures as parts of a single user message, in list order: their numbering in
     * the prompt («image 1», «image 2») relies on that order. The total size budget is counted over the
     * bytes actually read — an overrun aborts the call before HTTP rather than after buffering
     * everything.
     */
    private List<Map<String, Object>> contentParts(MediaCall call, String prompt, List<String> fileIds) {
        if (fileIds.size() > MAX_INPUT_IMAGES) {
            throw new MediaInferenceException("too many input images: " + fileIds.size()
                    + ", limit " + MAX_INPUT_IMAGES);
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(textPart(prompt));
        long total = 0;
        for (String fileId : fileIds) {
            LoadedImage image = loadImage(call, fileId);
            total += image.bytes().length;
            if (total > MAX_INPUT_TOTAL_BYTES) {
                throw new MediaInferenceException("input images are too large together ("
                        + total + " bytes, limit " + MAX_INPUT_TOTAL_BYTES + " per call)");
            }
            parts.add(imagePart(image.dataUri()));
        }
        return parts;
    }

    /** Bytes of an input picture together with its mime; it is expanded into a data URI when the request is assembled. */
    private record LoadedImage(String mime, byte[] bytes) {
        String dataUri() {
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        }
    }

    /**
     * An input picture: ownership through {@code open(userId, fileId)} (a foreign or expired file →
     * {@code StoredFileNotFoundException}), then the mime and size checks.
     */
    private LoadedImage loadImage(MediaCall call, String fileId) {
        FileContent content = fileStorageService.open(call.userId(), fileId);
        StoredFile file = content.file();
        if (file.getMime() == null || !file.getMime().startsWith("image/")) {
            throw new MediaInferenceException(
                    "file " + fileId + " is " + file.getMime() + ", not an image");
        }
        if (file.getSizeBytes() != null && file.getSizeBytes() > MAX_INPUT_IMAGE_BYTES) {
            throw new MediaInferenceException("file " + fileId + " is too large for vision ("
                    + file.getSizeBytes() + " bytes, limit " + MAX_INPUT_IMAGE_BYTES + ")");
        }
        try (InputStream in = content.content()) {
            return new LoadedImage(file.getMime(), in.readAllBytes());
        } catch (IOException e) {
            throw new MediaInferenceException("failed to read file " + fileId);
        }
    }

    /**
     * Token accounting through the same {@code llm_usage_log} and counters as the agent loop: over
     * chat/completions, image models bill a picture as output tokens. A response with no {@code usage}
     * gives zeroes plus a warning (the fact of the call stays in the log, and the shortfall is visible).
     */
    private void recordUsage(MediaCall call, ResolvedLlm resolved, Map<String, Object> response) {
        Usage usage = MediaInferenceHttp.usage(response);
        if (usage == null) {
            log.warn("media response carried no usage provider={} model={} — recording zeros",
                    resolved.provider().getId(), resolved.model());
            usage = new Usage(0, 0, null);
        }
        llmUsageService.record(new LlmUsageService.UsageReport(
                USAGE_CALL_PREFIX + call.callId(), call.runId(), call.agentId(), call.userId(),
                resolved.provider().getId(), resolved.model(),
                usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens(), null));
    }

    private static Map<String, Object> textPart(String text) {
        return Map.of("type", "text", "text", text);
    }

    private static Map<String, Object> imagePart(String dataUri) {
        return Map.of("type", "image_url", "image_url", Map.of("url", dataUri));
    }
}
