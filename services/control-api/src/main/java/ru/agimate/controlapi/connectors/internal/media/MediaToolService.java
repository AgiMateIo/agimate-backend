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
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Tools of the media connector. Files travel by reference (`agf_…`, docs/connectors/files.md): the
 * result of a generation is {@code {"file": {...}}}, and input pictures are id parameters. Every
 * domain failure of the LLM layer is translated into {@link ConnectorException} — the agent sees that
 * text verbatim and can relay it to the user.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MediaToolService {

    /** Generation and editing on slow image models can take a while — a 30-minute budget. */
    static final int GENERATION_TIMEOUT_SECONDS = 1800;
    /** Vision answers in seconds or minutes; a long hang is a failure, and we wait no more than 5 minutes. */
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
                mediaInferenceService.generateImage(call(), prompt, List.of())));
    }

    @Tool(name = "edit_image",
            description = "Create a new image from an existing one (agf_… file id) following the "
                    + "prompt — both for edits (change background, style, add or remove objects) and "
                    + "for drawing something new based on the source (same character, same style). "
                    + "Returns a new file — the original is left untouched. Same diffusion-model "
                    + "limits as gen_image: exact text, symbols, and precise positions aren't "
                    + "guaranteed — don't re-edit repeatedly chasing precision the model can't deliver.",
            annotations = @ToolAnnotations(destructiveHint = false),
            timeoutSeconds = GENERATION_TIMEOUT_SECONDS)
    public Map<String, Object> editImage(
            @ToolParam("Source image file id (agf_…)") String fileId,
            @ToolParam("What to change, or what to draw based on this image") String prompt) {
        return guard(() -> imageResult(
                mediaInferenceService.generateImage(call(), prompt, List.of(fileId))));
    }

    @Tool(name = "combine_images",
            description = "Build one new image out of several existing ones (2–4 agf_… file ids): "
                    + "put the person from one photo into the scene of another, place a product on a "
                    + "background, merge styles. The model sees the images in the order given — refer "
                    + "to them in the prompt as \"image 1\", \"image 2\", … and say what to take from "
                    + "each. Returns a new file, sources untouched. Not every image model supports "
                    + "multiple references: if the result clearly ignores one of them, say so instead "
                    + "of retrying — one more attempt at most.",
            annotations = @ToolAnnotations(destructiveHint = false),
            timeoutSeconds = GENERATION_TIMEOUT_SECONDS)
    public Map<String, Object> combineImages(
            @ToolParam("Source image file ids (agf_…), in the order the prompt refers to them")
            List<String> fileIds,
            @ToolParam("What the combined image should look like and what to take from each source")
            String prompt) {
        if (fileIds == null || fileIds.size() < 2) {
            throw new ConnectorException("combine_images needs at least two image ids; "
                    + "for a single source use edit_image");
        }
        return guard(() -> imageResult(
                mediaInferenceService.generateImage(call(), prompt, fileIds)));
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
     * Identity of the call, from {@link ConnectorEnv}. {@code callId} is a fresh uuid v8: the external
     * id of the tool_call_logs row is not threaded into the env, and the physical execution of a tool
     * is one per row anyway (async dispatch without retries) — so deduplicating the accounting here
     * only guards against a double call within a single execution.
     */
    private static MediaCall call() {
        ConnectorEnv env = ConnectorEnvHolder.current();
        if (env.userId() == null || env.agentId() == null) {
            throw new ConnectorException("media tools require agent identity");
        }
        return new MediaCall(env.userId(), env.agentId(), env.runId(),
                UUIDUtils.generateUUIDv8().toString());
    }

    /** A result by the files.md convention; a refusal by the model (no file) returns its text as the result. */
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
     * Domain exceptions of the LLM and file layers → {@link ConnectorException} (otherwise
     * {@code BaseConnectorHandler} would mask them behind a faceless «Tool execution failed»).
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
