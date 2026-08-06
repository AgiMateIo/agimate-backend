package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

@Schema(description = "Request to bind a skill to an agent")
public record CreateAgentSkillRequest(
        @NotNull
        @Schema(description = "Skill public ID to bind")
        UUID skillId,

        @Schema(description = "Which instance the skill means, per connector code. Required for every external "
                + "connector the skill declares; for internal ones the instance is forced and resolved by the server",
                example = "{\"telegram\": \"0198f2c1-...\"}")
        Map<String, UUID> connections
) {
    public Map<String, UUID> resolveConnections() {
        return connections == null ? Map.of() : connections;
    }
}
