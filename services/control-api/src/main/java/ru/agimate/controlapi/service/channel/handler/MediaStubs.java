package ru.agimate.controlapi.service.channel.handler;

import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.service.channel.handler.dto.Part;

import java.util.List;
import java.util.Locale;

/**
 * Текстовые заглушки для inbound-вложений — «описание загруженного файла» с его id. Служат
 * плейсхолдером вложения в истории (протокол v2 текстовый), подсказкой агенту (image воркер к тому
 * же подаёт в LLM как Media — «зрение») и источником id для повторной отправки через
 * {@code [[attach:agf_…]]}. Общий для webchat и Telegram.
 *
 * <p>Формулировка намеренно рамочная: явно сказано, что файл УЖЕ приложен и (для картинок) виден
 * напрямую, а id — только для ссылки, скачивать по нему не нужно. Иначе модель принимает
 * инлайн-картинку за «файл по id, который надо достать», и игнорирует зрение.
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

    /** Описание одного загруженного файла: рамка + id, framing зависит от того, «видит» ли его модель. */
    public static String stub(Part part) {
        String meta = metaSuffix(part);
        if ("image".equals(part.type())) {
            // Картинка идёт модели инлайном (Media) — подчёркиваем, что она уже видна, id лишь для ссылки.
            return "[Описание загруженного файла: изображение, уже приложено к этому сообщению — "
                    + "ты видишь его напрямую" + meta + ". id: " + part.storageRef()
                    + ". Файл уже доступен, скачивать по id не нужно; id — только чтобы сослаться на файл.]";
        }
        String kind = switch (part.type() == null ? "file" : part.type()) {
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
