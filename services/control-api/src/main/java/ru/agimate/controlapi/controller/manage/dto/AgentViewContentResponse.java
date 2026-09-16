package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "A view page to render in a sandboxed iframe (srcdoc, no allow-same-origin)")
public record AgentViewContentResponse(
        @Schema(description = "View resource")
        String uri,

        @Schema(description = "MIME type as served", example = "text/html;profile=mcp-app")
        String mimeType,

        @Schema(description = "The page itself")
        String html,

        @Schema(nullable = true, description = "_meta.ui.csp of the resource: connectDomains, resourceDomains, "
                + "frameDomains, baseUriDomains; null — the strictest policy")
        Map<String, Object> csp,

        @Schema(nullable = true, description = "_meta.ui.permissions the view asks for (camera, microphone, …)")
        Map<String, Object> permissions,

        @Schema(nullable = true, description = "_meta.ui.prefersBorder: whether the host should draw a frame")
        Boolean prefersBorder
) {
}
