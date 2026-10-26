package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Hide a skill's starter on this agent; hiding twice is fine")
public record HideAgentStarterRequest(
        @NotNull
        @Schema(description = "The binding the starter comes from (agentSkillId of the listing)")
        UUID agentSkillId,

        @NotBlank
        @Schema(description = "The starter's text, as listed")
        String text
) {
}
