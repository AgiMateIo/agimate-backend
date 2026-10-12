package ru.agimate.controlapi.controller.manage.dto.mcp;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Where the consent page sends the browser next")
public record McpRedirectResponse(
        @Schema(description = "The client's redirect_uri with a code or an error, state and iss")
        String redirectUrl
) {}
