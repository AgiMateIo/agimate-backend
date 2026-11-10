package ru.agimate.controlapi.controller.manage.dto.session;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A tool call of a session message as an inline view sees it (docs/decisions/connector-views.md):
 * who the call was for and whether it renders into a view. The view is resolved against the agent's
 * current catalog, so a revoked binding leaves {@code viewUri} null instead of hiding the call.
 *
 * @param toolCallId   the id the message's {@code toolCallIds} name
 * @param name         connector tool name
 * @param connectionId connection that served the call; null for a call without one
 * @param viewUri      the view the call renders into; null — the call has no view
 */
@Schema(description = "A tool call of a session message and the view it renders into")
public record ToolCallDescriptor(
        @Schema(description = "Tool call id — the one the message's toolCallIds name")
        String toolCallId,

        @Schema(description = "Connector tool name")
        String name,

        @Schema(nullable = true, description = "Connection that served the call; null when the call has none")
        String connectionId,

        @Schema(nullable = true, description = "View the call renders into; null when it has none "
                + "(no ui:// resource, or a binding since revoked)")
        String viewUri
) {
}
