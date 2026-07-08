package ru.agimate.controlapi.controller.manage.dto.webchat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

@Schema(description = "Send a message into a webchat session")
public record WebchatSendMessageRequest(
        @NotBlank
        @Schema(description = "Message text")
        String text,

        @Schema(description = "Attachments (reserved for files; must be empty for now)")
        List<Map<String, Object>> parts
) {
}
