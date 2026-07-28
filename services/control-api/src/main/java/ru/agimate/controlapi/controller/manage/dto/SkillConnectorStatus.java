package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Status of a skill's connector for a particular agent: whether the agent has an active connection of
 * that type. {@code connectionId == null} → the connector is not connected (the frontend offers to
 * connect it).
 */
@Schema(description = "Connector requirement of a skill and the agent's connection for it (null = not connected)")
public record SkillConnectorStatus(
        @Schema(description = "Connector code required by the skill")
        String connectorCode,

        @Schema(description = "Active connection id of this connector bound to the agent, or null")
        UUID connectionId
) {
}
