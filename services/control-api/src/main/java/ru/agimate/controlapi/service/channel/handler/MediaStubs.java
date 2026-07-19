package ru.agimate.controlapi.service.channel.handler;

import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.service.channel.handler.dto.Part;

import java.util.List;
import java.util.Locale;

/**
 * Текстовые заглушки для inbound-вложений: строка вида {@code [приложено изображение: agf_…, image/png, 375 KB]}.
 * Это одновременно плейсхолдер вложения в истории (протокол v2 текстовый) и подсказка агенту, что файл
 * приложен (image воркер к тому же подаёт в LLM как Media — «зрение»). Общий для webchat и Telegram.
 */
@UtilityClass
public class MediaStubs {

    /** Пользовательский текст + по строке-заглушке на каждое вложение (пустой текст — только заглушки). */
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

    /** Одна заглушка вложения. */
    public static String stub(Part part) {
        String kind = switch (part.type() == null ? "file" : part.type()) {
            case "image" -> "изображение";
            case "video" -> "видео";
            case "audio" -> "аудио";
            default -> "файл";
        };
        StringBuilder sb = new StringBuilder("[приложено ").append(kind).append(": ").append(part.storageRef());
        if (part.mime() != null && !part.mime().isBlank()) {
            sb.append(", ").append(part.mime());
        }
        if (part.size() > 0) {
            sb.append(", ").append(humanSize(part.size()));
        }
        return sb.append(']').toString();
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
