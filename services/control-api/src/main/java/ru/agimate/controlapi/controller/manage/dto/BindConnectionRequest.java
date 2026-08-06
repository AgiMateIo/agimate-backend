package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Exactly one of the two: an external instance is chosen by id, an internal connector by code — its
 * instance is the user's single mode row, and its id does not exist until something materialises it.
 */
@Schema(description = "Open a connector to an agent: connectionId for an external instance, "
        + "connectorCode for an internal one (memory/board/sheets/time/media)")
public record BindConnectionRequest(
        @Schema(description = "Connection (instance) id to bind — external connectors")
        UUID connectionId,

        @Schema(description = "Internal connector code to open — the instance is resolved by the server")
        String connectorCode
) {
    public boolean hasConnectionId() {
        return connectionId != null;
    }

    public boolean hasConnectorCode() {
        return connectorCode != null && !connectorCode.isBlank();
    }
}
