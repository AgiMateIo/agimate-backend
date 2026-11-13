package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.enums.ConnectorPresetAuth;

import java.util.List;
import java.util.Map;

@Schema(description = "Prefill of the connection form; the frontend owns the form itself")
public record ConnectorPresetForm(
        @Schema(description = "Non-secret form values; for MCP, the server URL")
        Map<String, String> params,

        @Schema(description = "Credential fields of the connector, minus what this preset hides or replaces")
        Map<String, CredentialFieldResponse> credentialFields,

        @Schema(description = "Extra HTTP headers of this preset")
        List<HeaderFieldResponse> headerFields,

        @Schema(description = "Headers of which the client sends exactly one")
        List<HeaderGroupResponse> headerGroups,

        @Schema(description = "Supported auth methods; empty means the card is not connectable")
        List<ConnectorPresetAuth> authMethods
) {
}
