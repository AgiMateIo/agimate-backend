package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Short reference to a skill bound to an agent")
public record AgentSkillSummary(
        @Schema(description = "Skill public ID")
        UUID pubId,

        @Schema(description = "Skill name")
        String name
) {
}
