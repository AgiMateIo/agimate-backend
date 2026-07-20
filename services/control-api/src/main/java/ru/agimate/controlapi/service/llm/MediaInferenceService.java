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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Гейт медиа-инференса «модель как инструмент» (docs/connectors/media.md): резолв модели по
 * назначению ({@link LlmCredentialsResolver#resolveForCapability}) → chat/completions →
 * байты в файловый слой → учёт usage. Единственная точка, где медиа-коннектор касается
 * LLM-инфраструктуры: сам коннектор не знает ни про провайдеров, ни про ключи, ни про реестр.
 *
 * <p>Ключ живёт только в кадре вызова (как в {@code GetLlmCredentials} — inline, без персиста).
 * Наружу — доменные исключения ({@link MediaInferenceException}, {@link NoCapableModelException},
 * {@link QuotaExceededException}, …); в {@code ConnectorException} их мапит коннектор.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MediaInferenceService {

    /** Префикс {@code call_id} в llm_usage_log: неймспейс от {@code llm_call}-воркера. */
    static final String USAGE_CALL_PREFIX = "media:";
    /** Потолок входной картинки: data-URI буферизуется в heap целиком. */
    private static final long MAX_INPUT_IMAGE_BYTES = 20L * 1024 * 1024;
    private static final String DEFAULT_DESCRIBE_PROMPT = "Describe this image in detail.";

    private final LlmCredentialsResolver credentialsResolver;
    private final MediaInferenceHttp http;
    private final FileStorageService fileStorageService;
    private final LlmUsageService llmUsageService;

    /**
     * Идентичность вызова: владелец файлов/квот + агент-инициатор + идемпотентный id
     * (у коннектора — external id строки tool_call_logs).
     */
    public record MediaCall(UUID userId, UUID agentId, String callId) {
    }

    /**
     * Результат генерации. {@code file == null} — модель ответила текстом без картинки
     * (safety-отказ и т.п.); {@code text} отдаётся агенту как есть, это результат, не ошибка.
     */
    public record ImageResult(StoredFile file, String text) {
    }

    /**
     * Генерация ({@code sourceFileId == null}) или редактирование картинки по промпту.
     * Результат — файл в storage с провенансом {@code media:<model>} и дефолтным TTL.
     */
    public ImageResult generateImage(MediaCall call, String prompt, String sourceFileId) {
        ResolvedLlm resolved = credentialsResolver.resolveForCapability(
                call.agentId(), call.userId(), LlmPurpose.IMAGE);
        Object content = sourceFileId == null
                ? prompt
                : List.of(textPart(prompt), imagePart(loadImageDataUri(call, sourceFileId)));
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

    /** Зрение по файлу: описание/ответ на вопрос об изображении {@code fileId}. */
    public String describeImage(MediaCall call, String fileId, String question) {
        ResolvedLlm resolved = credentialsResolver.resolveForCapability(
                call.agentId(), call.userId(), LlmPurpose.VISION);
        String prompt = question == null || question.isBlank() ? DEFAULT_DESCRIBE_PROMPT : question;
        Map<String, Object> body = requestBody(resolved, Map.of(
                "messages", List.of(Map.of("role", "user", "content",
                        List.of(textPart(prompt), imagePart(loadImageDataUri(call, fileId)))))));

        Map<String, Object> response = http.chatCompletions(resolved.provider(), resolved.apiKey(), body);
        recordUsage(call, resolved, response);
        return MediaInferenceHttp.messageText(response);
    }

    /** Итоговое тело запроса: extra_body резолва под низом, ядро (model/messages) побеждает. */
    private static Map<String, Object> requestBody(ResolvedLlm resolved, Map<String, Object> core) {
        Map<String, Object> withModel = new LinkedHashMap<>(core);
        withModel.put("model", resolved.model());
        return ExtraBodyMerge.merge(resolved.extraBody(), withModel);
    }

    /**
     * Входная картинка как data-URI: ownership через {@code open(userId, fileId)} (чужой/протухший
     * файл → {@code StoredFileNotFoundException}), затем проверки mime и размера.
     */
    private String loadImageDataUri(MediaCall call, String fileId) {
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
            return "data:" + file.getMime() + ";base64,"
                    + Base64.getEncoder().encodeToString(in.readAllBytes());
        } catch (IOException e) {
            throw new MediaInferenceException("failed to read file " + fileId);
        }
    }

    /**
     * Учёт токенов тем же {@code llm_usage_log}/счётчиками, что и агентный цикл: image-модели за
     * chat/completions тарифицируют картинку output-токенами. Ответ без {@code usage} — нули с
     * warn'ом (факт вызова в логе остаётся, недосчёт виден).
     */
    private void recordUsage(MediaCall call, ResolvedLlm resolved, Map<String, Object> response) {
        Usage usage = MediaInferenceHttp.usage(response);
        if (usage == null) {
            log.warn("media response carried no usage provider={} model={} — recording zeros",
                    resolved.provider().getId(), resolved.model());
            usage = new Usage(0, 0, null);
        }
        llmUsageService.record(new LlmUsageService.UsageReport(
                USAGE_CALL_PREFIX + call.callId(), null, call.agentId(), call.userId(),
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
