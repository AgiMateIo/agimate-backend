package ru.agimate.controlapi.service.webchat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * An attachment of a webchat message as the frontend sees it (the Centrifugo event and the
 * {@code /manage/webchat} history). In {@code webchat_messages.parts} it is stored without the
 * {@code url} (type/fileId/mime/size): signed links expire, so fresh ones are issued on every read or
 * publication.
 *
 * @param url relative signed URL of the contents ({@code /files/agf_…?exp&sig}); the control-api
 *            origin is added by the frontend
 */
public record WebchatAttachment(
        String type,
        String fileId,
        String mime,
        Long size,
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
                url);
    }

    /** Stored parts plus fresh links from {@code urlIssuer} (fileId → a signed URL); null when the input is empty. */
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
