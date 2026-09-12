package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.model.ConnectorRequirement;

import java.util.List;

/**
 * {@code connectorCodes} is the pre-requirement shape (a bare list of codes) and is honoured when
 * {@code connectors} is absent, so a client that has not moved yet keeps working.
 */
@Schema(description = "Request to replace the skill's connector requirements")
public record UpdateSkillConnectorsRequest(
        @Schema(description = "Connector requirements (code, key, title, params, tools, triggers); "
                + "key defaults to code. Empty list = skill without connectors", nullable = true)
        List<ConnectorRequirement> connectors,
        @Schema(description = "Legacy: bare connector codes; used only when connectors is absent", nullable = true)
        List<String> connectorCodes
) {
    public List<ConnectorRequirement> resolveConnectors() {
        if (connectors != null) {
            return connectors;
        }
        return connectorCodes == null ? List.of() : ConnectorRequirement.ofCodes(connectorCodes);
    }
}
