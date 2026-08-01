package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.controller.manage.ManageConnectionController;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.enums.ConnectionAuthStatus;

/**
 * Result of creating a connection. «This one needs OAuth» is not a property of the connector's fields
 * and is not declared anywhere in advance — with detection by 401 the connector itself does not know
 * it statically, it learns it from the live server. So the sign travels here, in the answer, and the
 * field descriptors stay about what a user types by hand.
 */
@Schema(description = "Created connection, plus what has to happen next")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConnectionCreatedResponse(
        @Schema(description = "The connection itself")
        ConnectionResponse connection,

        @Schema(description = "ready — usable right away; authorization_required — the user must authorize",
                allowableValues = {"ready", "authorization_required"})
        String status,

        @Schema(description = "Where to POST to start authorization; null when nothing else is needed")
        String authorizeUrl
) {
    public static ConnectionCreatedResponse from(Connection connection) {
        boolean pending = connection.getAuthStatus() == ConnectionAuthStatus.PENDING_AUTH;
        return new ConnectionCreatedResponse(
                ConnectionResponse.from(connection),
                pending ? "authorization_required" : "ready",
                pending ? ManageConnectionController.PATH + "/" + connection.getId() + "/authorize" : null);
    }
}
