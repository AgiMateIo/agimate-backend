package ru.agimate.controlapi.connectors.internal.media;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.common.util.UUIDUtils;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.service.llm.LlmProviderDisabledException;
import ru.agimate.controlapi.service.llm.MediaInferenceException;
import ru.agimate.controlapi.service.llm.MediaInferenceService;
import ru.agimate.controlapi.service.llm.MediaInferenceService.ImageResult;
import ru.agimate.controlapi.service.llm.MediaInferenceService.MediaCall;
import ru.agimate.controlapi.service.llm.NoCapableModelException;
import ru.agimate.controlapi.service.llm.QuotaExceededException;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.FileRejectedException;
import ru.agimate.controlapi.storage.StoredFileNotFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Тулы media-коннектора. Файлы ходят pass-by-reference (`agf_…`, docs/connectors/files.md):
 * результат генерации — {@code {"file": {...}}}, входные картинки — параметром-строкой.
 * Все доменные сбои LLM-слоя переводятся в {@link ConnectorException} — их текст агент видит
 * дословно и может пересказать пользователю.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MediaToolService {

    /** Генерация/редактирование у медленных image-моделей может идти долго — бюджет 30 минут. */
    static final int GENERATION_TIMEOUT_SECONDS = 1800;
    /** Зрение отвечает за секунды-минуты; долгое зависание — сбой, дольше 5 минут не ждём. */
    static final int VISION_TIMEOUT_SECONDS = 300;

    private final MediaInferenceService mediaInferenceService;

    @Tool(name = "gen_image",
            description = "Generate an image from a text prompt using an image-capable model. "
                    + "Returns {\"file\": {\"id\": \"agf_…\"}} — attach the image to your reply "
                    + "with [[attach:agf_…]] or pass the id to another tool. If the model declines, "
                    + "you get its textual reply instead of a file. This is a diffusion model: it "
                    + "cannot reliably render exact text, symbols, counts, or precise positions and "
                    + "geometry. Don't expect pixel-accuracy — verify the result at most once; if it "
                    + "isn't perfectly exact, deliver the best result you have and note the limitation "
                    + "instead of regenerating repeatedly.",
            annotations = @ToolAnnotations(destructiveHint = false),
            timeoutSeconds = GENERATION_TIMEOUT_SECONDS)
    public Map<String, Object> genImage(
            @ToolParam("Detailed description of the image to generate") String prompt) {
        return guard(() -> imageResult(
                mediaInferenceService.generateImage(call(), prompt, null)));
    }

    @Tool(name = "edit_image",
            description = "Create a modified version of an existing image (agf_… file id) following "
                    + "the prompt: change background, style, add or remove objects, etc. Returns a new "
                    + "file — the original is left untouched. Same diffusion-model limits as gen_image: "
                    + "exact text, symbols, and precise positions aren't guaranteed — don't re-edit "
                    + "repeatedly chasing precision the model can't deliver.",
            annotations = @ToolAnnotations(destructiveHint = false),
            timeoutSeconds = GENERATION_TIMEOUT_SECONDS)
    public Map<String, Object> editImage(
            @ToolParam("Source image file id (agf_…)") String fileId,
            @ToolParam("What to change in the image") String prompt) {
        return guard(() -> imageResult(
                mediaInferenceService.generateImage(call(), prompt, fileId)));
    }

    @Tool(name = "read_image",
            description = "Look at an image file (agf_… id) with a vision-capable model: describe it "
                    + "or answer a question about it. Use for files from history, screenshots or "
                    + "generated images. Not needed for an image already attached to the current "
                    + "message when you can see it directly.",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true),
            timeoutSeconds = VISION_TIMEOUT_SECONDS)
    public Map<String, Object> readImage(
            @ToolParam("Image file id (agf_…)") String fileId,
            @ToolParam(value = "Question about the image (default: describe it in detail)",
                    required = false) String question) {
        return guard(() -> Map.of(
                "answer", mediaInferenceService.describeImage(call(), fileId, question)));
    }

    /**
     * Идентичность вызова из {@link ConnectorEnv}. {@code callId} — свежий uuid v8: external id
     * строки tool_call_logs в env не прокинут, а физическое исполнение тула и так одно на строку
     * (async-диспатч без ретраев) — дедуп учёта здесь страхует только от двойного вызова внутри
     * одного исполнения.
     */
    private static MediaCall call() {
        ConnectorEnv env = ConnectorEnvHolder.current();
        if (env.userId() == null || env.agentId() == null) {
            throw new ConnectorException("media tools require agent identity");
        }
        return new MediaCall(env.userId(), env.agentId(), env.runId(),
                UUIDUtils.generateUUIDv8().toString());
    }

    /** Результат по конвенции files.md; отказ модели (без файла) — текст как результат. */
    private static Map<String, Object> imageResult(ImageResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        StoredFile file = result.file();
        if (file != null) {
            out.put("file", Map.of(
                    "id", FileIds.external(file.getId()),
                    "mime", file.getMime(),
                    "size", file.getSizeBytes()));
        }
        if (result.text() != null && !result.text().isBlank()) {
            out.put("text", result.text());
        }
        if (out.isEmpty()) {
            throw new ConnectorException("model returned neither an image nor text");
        }
        return out;
    }

    /**
     * Доменные исключения LLM/файлового слоя → {@link ConnectorException} (иначе
     * {@code BaseConnectorHandler} замаскирует их в безликое «Tool execution failed»).
     */
    private static <T> T guard(Supplier<T> action) {
        try {
            return action.get();
        } catch (MediaInferenceException | NoCapableModelException | QuotaExceededException
                 | LlmProviderDisabledException | FileRejectedException
                 | StoredFileNotFoundException e) {
            throw new ConnectorException(e.getMessage(), e);
        }
    }
}
