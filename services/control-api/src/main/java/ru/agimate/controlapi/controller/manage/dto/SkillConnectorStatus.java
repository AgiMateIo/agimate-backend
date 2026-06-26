package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Статус коннектора скилла для конкретного агента: есть ли у агента активный коннекшен этого типа.
 * {@code connectionId == null} → коннектор не подключён (фронт предлагает подключить).
 */
@Schema(description = "Connector requirement of a skill and the agent's connection for it (null = not connected)")
public record SkillConnectorStatus(
        @Schema(description = "Connector code required by the skill")
        String connectorCode,

        @Schema(description = "Active connection id of this connector bound to the agent, or null")
        UUID connectionId
) {
}
