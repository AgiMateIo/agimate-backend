package ru.agimate.controlapi.controller.app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.storage.FileIds;

import java.time.LocalDateTime;

@Schema(description = "Stored file metadata (connector file layer, docs/connectors/files.md)")
public record FileResponse(
        @Schema(description = "Public file id (agf_<uuid>) — usable as a FileRef tool parameter")
        String id,

        @Schema(description = "MIME type")
        String mime,

        @Schema(description = "Size in bytes")
        long size,

        @Schema(description = "Hex SHA-256 of the content")
        String sha256,

        @Schema(description = "Expiration timestamp (TTL)")
        LocalDateTime expiresAt
) {

    public static FileResponse from(StoredFile file) {
        return new FileResponse(FileIds.external(file.getId()), file.getMime(),
                file.getSizeBytes(), file.getSha256(), file.getExpiresAt());
    }
}
