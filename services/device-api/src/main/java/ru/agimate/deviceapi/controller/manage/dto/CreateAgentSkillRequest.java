package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Request to bind a skill to an agent")
public record CreateAgentSkillRequest(
        @NotNull
        @Schema(description = "Skill public ID to bind")
        UUID skillPubId
) {
}
