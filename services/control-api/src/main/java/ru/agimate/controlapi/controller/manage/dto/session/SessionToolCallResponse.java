package ru.agimate.controlapi.controller.manage.dto.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.connectors.core.dto.ViewCallResult;
import ru.agimate.controlapi.controller.manage.dto.ViewToolCallResponse;
import ru.agimate.controlapi.database.entities.ToolCallLog;

import java.util.Map;

/**
 * A tool call of a session message as an inline view sees it (docs/decisions/connector-views.md):
 * which view it renders into, the arguments the model sent, and the {@code CallToolResult} once the
 * call settles. The message itself carries only the id — this is the resolution the frontend makes
 * when it mounts the view.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A tool call of a session message and the view it renders into")
public record SessionToolCallResponse(
        @Schema(description = "Tool call id — the one the message's toolCallIds name")
        String toolCallId,

        @Schema(description = "Connector tool name")
        String name,

        @Schema(description = "Connection that served the call")
        String connectionId,

        @Schema(nullable = true, description = "View the call renders into; null when the tool has no view "
                + "(no ui:// resource, or a binding since revoked)")
        String viewUri,

        @Schema(nullable = true, description = "Arguments the model sent; null when there is no view")
        Map<String, Object> input,

        @Schema(nullable = true, description = "The call's result as an MCP CallToolResult; null while it runs "
                + "or when there is no view")
        ViewToolCallResponse result
) {

    /** The call resolved to no view — identity only, so a caller asking about every call stays cheap. */
    public static SessionToolCallResponse withNoView(ToolCallLog log) {
        return new SessionToolCallResponse(log.getExternalId(), log.getName(), log.getConnectionId(),
                null, null, null);
    }

    /** @param result null while the call has not settled */
    public static SessionToolCallResponse of(ToolCallLog log, String viewUri, ViewCallResult result) {
        return new SessionToolCallResponse(log.getExternalId(), log.getName(), log.getConnectionId(),
                viewUri, log.getInput(), result == null ? null : ViewToolCallResponse.of(result));
    }
}
