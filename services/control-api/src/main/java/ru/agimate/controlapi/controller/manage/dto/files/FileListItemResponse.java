package ru.agimate.controlapi.controller.manage.dto.files;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.service.channel.handler.dto.Part;
import ru.agimate.controlapi.storage.FileIds;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "A file of the current user (connector file layer, docs/connectors/files.md)")
public record FileListItemResponse(
        @Schema(description = "Public file id (agf_<uuid>)")
        String id,

        @Schema(description = "File name; null when the producer had none (a Telegram photo, a generated image)")
        String name,

        @Schema(description = "Attachment type derived from the MIME: image | video | audio | file")
        String type,

        @Schema(description = "MIME type")
        String mime,

        @Schema(description = "Size in bytes")
        long size,

        @Schema(description = "The agent that produced the file; null for uploads and inbound messages")
        UUID agentId,

        @Schema(description = "Provenance of the producer: connector/tool/connection")
        String origin,

        @Schema(description = "When the file was stored")
        LocalDateTime createdAt,

        @Schema(description = "When it will be swept (TTL)")
        LocalDateTime expiresAt,

        @Schema(description = "Signed URL of the contents; relative to the control-api origin, or absolute when the object store signed it (app.files.presign). Expires with app.files.url-ttl")
        String url
) {

    /** @param url a freshly signed link — one is never stored, it would go stale */
    public static FileListItemResponse from(StoredFile file, String url) {
        return new FileListItemResponse(
                FileIds.external(file.getId()),
                file.getName(),
                Part.typeForMime(file.getMime()),
                file.getMime(),
                file.getSizeBytes(),
                file.getAgentId(),
                file.getOrigin(),
                file.getCreatedAt(),
                file.getExpiresAt(),
                url);
    }
}
