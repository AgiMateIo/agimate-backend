package ru.agimate.controlapi.controller.manage.dto.webchat;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Sending a message into a webchat session. {@code text} and {@code parts} are individually optional,
 * but at least one must be non-empty (checked in the service). Each element of {@code parts} carries
 * {@code {"fileId": "agf_…"}} — a file uploaded earlier through {@code POST /manage/files}.
 */
@Schema(description = "Send a message into a webchat session")
public record WebchatSendMessageRequest(
        @Schema(description = "Message text (optional when parts are present)")
        String text,

        @Schema(description = "Attachments: [{\"fileId\": \"agf_…\"}] from POST /manage/files")
        List<Map<String, Object>> parts
) {
}
