package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * One connector a skill declares, seen from a particular agent: which instance the skill means and
 * whether the agent can actually reach it.
 *
 * <p>{@link #connectionId} comes from the skill's own reference; for bindings made before references
 * existed it falls back to any active connection of that code — so an agent that works today does not
 * turn red for something the user never had a chance to choose. The fallback is transitional and goes
 * away once the tool gate moves from the code to the instance.
 *
 * @param satisfied the instance is bound to the agent. {@code false} means the skill declares
 *                  something the agent cannot reach — its tools will not be in the context
 */
@Schema(description = "Connector required by a skill: which instance it means and whether the agent has it")
public record SkillConnectorStatus(
        @Schema(description = "Connector code required by the skill")
        String connectorCode,

        @Schema(description = "Instance the skill works with; null — none chosen and none available")
        UUID connectionId,

        @Schema(description = "Human-readable name of that instance")
        String connectionName,

        @Schema(description = "Internal connector: the instance is forced (one per user), nothing to choose")
        boolean internal,

        @Schema(description = "The instance is bound to the agent — the skill's tools will be there")
        boolean satisfied
) {
}
