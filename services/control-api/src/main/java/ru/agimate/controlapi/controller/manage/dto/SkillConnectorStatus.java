package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * One connector a skill declares, seen from a particular agent: which instance the skill means and
 * whether the agent can actually reach it.
 *
 * <p>{@link #connectionId} comes from the skill's own reference, and where there is none — from any
 * active connection of that code, so an agent does not turn red for something the user was never asked
 * to choose. That is the standing rule, not a migration step: a code loses its reference whenever the
 * skill's author adds a connector to a skill someone has already bound. Where several instances answer,
 * the status shows the first while the gate lets all of them through.
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
