package ru.agimate.controlapi.controller.manage.dto.webchat;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.storage.FileIds;

import java.time.LocalDateTime;

/**
 * Метаданные файла, загруженного пользователем для webchat-сообщения. {@code fileId} — ключ,
 * который фронт кладёт в {@code parts} при отправке (OpenAPI, /control/manage/webchat).
 */
@Schema(description = "Uploaded webchat file metadata")
public record WebchatFileResponse(
        @Schema(description = "Public file id (agf_<uuid>) — put into send parts as {\"fileId\": ...}")
        String fileId,

        @Schema(description = "MIME type")
        String mime,

        @Schema(description = "Size in bytes")
        long size,

        @Schema(description = "Expiration timestamp (TTL)")
        LocalDateTime expiresAt
) {

    public static WebchatFileResponse from(StoredFile file) {
        return new WebchatFileResponse(FileIds.external(file.getId()), file.getMime(),
                file.getSizeBytes(), file.getExpiresAt());
    }
}
