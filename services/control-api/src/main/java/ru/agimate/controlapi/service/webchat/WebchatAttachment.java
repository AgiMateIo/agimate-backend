package ru.agimate.controlapi.service.webchat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Вложение webchat-сообщения, как его видит фронт (Centrifugo-событие и история
 * {@code /manage/webchat}). В {@code webchat_messages.parts} хранится без {@code url}
 * (type/fileId/mime/size): подписанные ссылки протухают, поэтому выдаются свежими на каждом
 * чтении/публикации.
 *
 * @param url относительный подписанный URL содержимого ({@code /files/agf_…?exp&sig});
 *            origin control-api добавляет фронт
 */
public record WebchatAttachment(
        String type,
        String fileId,
        String mime,
        Long size,
        String url
) {

    /** Из хранимого представления ({@code webchat_messages.parts}) + свежей ссылки. */
    public static WebchatAttachment fromStored(Map<String, Object> stored, String url) {
        Object size = stored.get("size");
        return new WebchatAttachment(
                (String) stored.get("type"),
                (String) stored.get("fileId"),
                (String) stored.get("mime"),
                size instanceof Number number ? number.longValue() : null,
                url);
    }

    /** Хранимые parts + свежие ссылки от {@code urlIssuer} (fileId → подписанный URL); null при пустом входе. */
    public static List<WebchatAttachment> fromStored(List<Map<String, Object>> storedParts,
                                                     Function<String, String> urlIssuer) {
        if (storedParts == null || storedParts.isEmpty()) {
            return null;
        }
        return storedParts.stream()
                .map(stored -> fromStored(stored, urlIssuer.apply((String) stored.get("fileId"))))
                .toList();
    }
}
