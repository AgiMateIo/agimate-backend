package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;

import java.util.LinkedHashMap;
import java.util.Map;

@Schema(description = "Integration-specific metadata for INTEGRATION-type connectors")
public record IntegrationMeta(
        @Schema(description = "Credentials form: field code to its declaration, in the order to render")
        Map<String, CredentialFieldResponse> credentialFields,

        @Schema(description = "Whether the integration supports webhooks")
        boolean supportsWebhooks
) {
    public static IntegrationMeta from(IntegrationConnectorHandler handler) {
        Map<String, CredentialFieldResponse> fields = new LinkedHashMap<>();
        handler.getCredentialFields().forEach((code, field) ->
                fields.put(code, CredentialFieldResponse.from(field)));
        return new IntegrationMeta(fields, handler.supportsWebhooks());
    }
}
