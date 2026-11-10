package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.connectors.core.dto.ViewCsp;

import java.util.Map;

@Schema(description = "A view page to render in a sandboxed iframe (srcdoc, no allow-same-origin). External "
        + "domains come only from the installation's allow-list; null means the strictest CSP — none at all")
public record AgentViewContentResponse(
        @Schema(description = "View resource")
        String uri,

        @Schema(description = "MIME type as served", example = "text/html;profile=mcp-app")
        String mimeType,

        @Schema(description = "The page itself")
        String html,

        @Schema(nullable = true, description = "_meta.ui.permissions the view asks for (camera, microphone, …)")
        Map<String, Object> permissions,

        @Schema(nullable = true, description = "_meta.ui.prefersBorder: whether the host should draw a frame")
        Boolean prefersBorder,

        @Schema(nullable = true, description = "_meta.ui.csp domains granted by the installation; null — none")
        ViewCsp csp
) {
}
