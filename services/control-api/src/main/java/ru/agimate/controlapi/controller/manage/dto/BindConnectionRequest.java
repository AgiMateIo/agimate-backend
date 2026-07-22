package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Bind an external connection instance to an agent. "
        + "Internal connectors (board/memory/time/media) are managed by skills, not by this endpoint.")
public record BindConnectionRequest(
        @NotNull
        @Schema(description = "Connection (instance) id to bind", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID connectionId
) {
}
