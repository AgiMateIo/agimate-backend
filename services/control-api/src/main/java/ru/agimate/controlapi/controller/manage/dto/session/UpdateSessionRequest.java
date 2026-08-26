package ru.agimate.controlapi.controller.manage.dto.session;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ru.agimate.controlapi.service.session.AgentSessionService;

@Schema(description = "Rename a session")
public record UpdateSessionRequest(
        @NotBlank
        @Size(max = AgentSessionService.TITLE_MAX_LENGTH)
        @Schema(description = "New title; a title derived from the first message is capped at the same length")
        String title
) {
}
