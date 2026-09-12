package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import ru.agimate.controlapi.database.enums.Disclosure;

import java.util.Map;
import java.util.UUID;

@Schema(description = "Request to bind a skill to an agent")
public record CreateAgentSkillRequest(
        @NotNull
        @Schema(description = "Skill public ID to bind")
        UUID skillId,

        @Schema(description = "Which instance the skill means, per requirement key (the connector code unless the "
                + "skill declares a key). An external requirement left out is bound unsatisfied until the wizard "
                + "chooses it; for internal ones the instance is forced and resolved by the server",
                example = "{\"telegram\": \"0198f2c1-...\", \"context7\": \"0198f2c2-...\"}")
        Map<String, UUID> connections,

        @Schema(nullable = true, description = "Override of the skill's disclosure axis for this agent; "
                + "absent — the skill's own axis applies")
        Disclosure disclosure
) {
    public Map<String, UUID> resolveConnections() {
        return connections == null ? Map.of() : connections;
    }
}
