package ru.agimate.controlapi.service.webchat;

import ru.agimate.controlapi.storage.FileLink;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * An attachment of a webchat message as the frontend sees it (the Centrifugo event and the
 * {@code /manage/webchat} history). In {@code webchat_messages.parts} it is stored without the
 * {@code url} (type/fileId/mime/size): signed links expire, so fresh ones are issued on every read or
 * publication.
 *
 * @param name the file name when the producer knew one; {@code null} for a Telegram photo or a
 *             generated image, where there is none to show
 * @param url  freshly signed URL of the contents — either relative ({@code /files/agf_…?exp&sig},
 *             the control-api origin is added by the frontend) or absolute, when the object store
 *             signed it itself ({@code app.files.presign}); the frontend follows it as it comes
 */
public record WebchatAttachment(
        String type,
        String fileId,
        String mime,
        Long size,
        String name,
        String url
) {

    /** From the stored representation ({@code webchat_messages.parts}) plus a fresh link. */
    public static WebchatAttachment fromStored(Map<String, Object> stored, String url) {
        Object size = stored.get("size");
        return new WebchatAttachment(
                (String) stored.get("type"),
                (String) stored.get("fileId"),
                (String) stored.get("mime"),
                size instanceof Number number ? number.longValue() : null,
                (String) stored.get("name"),
                url);
    }

    /**
     * Stored parts plus fresh links from {@code urlIssuer}; null when the input is empty. The link
     * spec is assembled from the row itself — the stored part already carries everything signing
     * needs, so a listing costs no reads of {@code files}.
     *
     * @param userId owner of the session, and so of every file mentioned in its messages
     */
    public static List<WebchatAttachment> fromStored(List<Map<String, Object>> storedParts, UUID userId,
                                                     Function<FileLink, String> urlIssuer) {
        if (storedParts == null || storedParts.isEmpty()) {
            return null;
        }
        return storedParts.stream()
                .map(stored -> fromStored(stored, urlIssuer.apply(new FileLink(
                        userId,
                        (String) stored.get("fileId"),
                        (String) stored.get("mime"),
                        (String) stored.get("name")))))
                .toList();
    }
}
