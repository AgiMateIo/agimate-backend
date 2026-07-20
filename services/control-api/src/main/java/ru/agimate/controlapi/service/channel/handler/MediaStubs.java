package ru.agimate.controlapi.service.channel.handler;

import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.service.channel.handler.dto.Part;

import java.util.List;
import java.util.Locale;

/**
 * Текстовые заглушки для inbound-вложений — «описание загруженного файла» с его id. Служат
 * плейсхолдером вложения в истории (протокол v2 текстовый), подсказкой агенту и источником id для
 * повторной отправки через {@code [[attach:agf_…]]}. Общий для webchat и Telegram.
 *
 * <p>Формулировка нейтральна к «зрению»: подаст ли воркер картинку модели инлайном (Media),
 * зависит от {@code input_modalities} chat-модели и решается на каждый LLM-вызов — рамку
 * «ты видишь / не видишь изображения» добавляет воркер system-подсказкой
 * ({@code LlmMessageMapper}), стаб утверждать этого не должен.
 */
@UtilityClass
public class MediaStubs {

    /** Пользовательский текст + по строке-описанию на каждое вложение (пустой текст — только описания). */
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

    /** Описание одного загруженного файла: тип + id; про видимость картинки не утверждает ничего. */
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

    /** Имя файла из meta, если известно (например Telegram document). */
    private static String name(Part part) {
        Object n = part.meta() != null ? part.meta().get("name") : null;
        return n != null && !n.toString().isBlank() ? " «" + n + "»" : "";
    }

    /** «, mime, размер» — общая часть описания. */
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
