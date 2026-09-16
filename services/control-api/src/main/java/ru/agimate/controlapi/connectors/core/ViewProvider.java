package ru.agimate.controlapi.connectors.core;

import ru.agimate.controlapi.connectors.core.dto.ToolUi;
import ru.agimate.controlapi.connectors.core.dto.ViewCallResult;
import ru.agimate.controlapi.connectors.core.dto.ViewPage;

/**
 * A connector capability: views — MCP Apps pages its tools link to through {@link ToolUi#resourceUri()}.
 * Which uri may be read and which tool may be called is decided by the caller from the agent's catalog;
 * the provider only knows how to serve a page and what its own tool results look like.
 *
 * <p>The env carries the connection and the agent the view is opened for; credentials are decrypted.
 */
public interface ViewProvider {

    /** The page of a view; {@link ConnectorException} when it cannot be served or is not an MCP App page. */
    ViewPage readView(ConnectorEnv env, String uri);

    /**
     * A successful tool output as recorded in the log, in the {@code CallToolResult} shape a view expects
     * from {@code tools/call}. The provider decides because only it knows the shape of its own outputs.
     */
    ViewCallResult toViewResult(String output);
}
