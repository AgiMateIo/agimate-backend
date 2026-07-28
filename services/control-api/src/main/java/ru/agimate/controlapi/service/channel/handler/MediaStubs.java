package ru.agimate.controlapi.service.channel.handler;

import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.service.channel.handler.dto.Part;

import java.util.List;
import java.util.Locale;

/**
 * Text stubs for inbound attachments — «a description of the uploaded file» with its id. They serve as
 * an attachment placeholder in history (protocol v2 is textual), as a hint to the agent and as the
 * source of an id for re-sending through {@code [[attach:agf_…]]}. Shared by webchat and Telegram.
 *
 * <p>The wording is neutral about «vision»: whether the worker feeds the picture to the model inline
 * (as Media) depends on the chat model's {@code input_modalities} and is decided per LLM call — the
 * «you can / cannot see images» framing is added by the worker as a system hint
 * ({@code LlmMessageMapper}), and a stub must assert nothing of the sort.
 */
@UtilityClass
public class MediaStubs {

    /** The user's text plus one description line per attachment (with empty text — the descriptions alone). */
    public static String withStubs(String userText, List<Part> parts) {
        if (parts == null || parts.isEmpty()) {
            return userText != null ? userText : "";
        }
        StringBuilder sb = new StringBuilder();
        if (userText != null && !userText.isBlank()) {
            sb.append(userText.strip());
        }
        for (Part part : parts) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(stub(part));
        }
        return sb.toString();
    }

    /** Description of one uploaded file: type plus id; it asserts nothing about whether a picture is visible. */
    public static String stub(Part part) {
        String meta = metaSuffix(part);
        String kind = switch (part.type() == null ? "file" : part.type()) {
            case "image" -> "изображение";
            case "video" -> "видео";
            case "audio" -> "аудио";
            default -> "файл";
        };
        return "[Описание загруженного файла: " + kind + name(part) + meta
                + ". id: " + part.storageRef() + ". Файл уже загружен и доступен по этому id.]";
    }

    /** File name from the meta, when known (a Telegram document, for instance). */
    private static String name(Part part) {
        Object n = part.meta() != null ? part.meta().get("name") : null;
        return n != null && !n.toString().isBlank() ? " «" + n + "»" : "";
    }

    /** «, mime, size» — the shared part of a description. */
    private static String metaSuffix(Part part) {
        StringBuilder sb = new StringBuilder();
        if (part.mime() != null && !part.mime().isBlank()) {
            sb.append(", ").append(part.mime());
        }
        if (part.size() > 0) {
            sb.append(", ").append(humanSize(part.size()));
        }
        return sb.toString();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
