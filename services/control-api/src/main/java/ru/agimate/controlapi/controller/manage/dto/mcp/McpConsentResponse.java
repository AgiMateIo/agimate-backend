package ru.agimate.controlapi.controller.manage.dto.mcp;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.service.mcp.oauth.ConsentView;

@Schema(description = "Either what the consent page shows, or where to send the browser right away")
public record McpConsentResponse(
        @Schema(description = "Set when the request is invalid but its client is verified: navigate here, the error goes back to the client")
        String redirectUrl,

        @Schema(description = "Set when the request is valid: the client and the user's agents")
        ConsentView consent
) {}
