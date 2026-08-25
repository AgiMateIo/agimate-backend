package ru.agimate.controlapi.connectors.core;

import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;

import java.util.Map;

/**
 * A connector capability: the tools available to the LLM. Implemented by the facade (usually through
 * {@link BaseConnectorHandler}, which builds the specs by reflection over the tool service's
 * {@code @Tool} methods); dynamic connectors (MCP) implement it directly.
 */
public interface ToolProvider {

    Map<String, ConnectorToolSpec> getTools();

    /**
     * Tool specs for one particular connector instance. By default they equal the static
     * {@link #getTools()} — most connectors do not depend on the instance. Dynamic connectors (MCP,
     * for example) override this: their tool set is discovered at runtime per connectionId, so here
     * they return the list for {@code env.connectionId()} (for MCP, out of the
     * {@code connection_tools} cache). The context carries connectionId; listing needs no decrypted
     * credentials.
     */
    default Map<String, ConnectorToolSpec> getTools(ConnectorEnv env) {
        return getTools();
    }

    /**
     * Whether the tools only work inside the live session of their own prompt channel (the IDE
     * connector: fs/terminal live in the ACP connection). {@code true} → a run whose prompt channel
     * is a different one gets no such tools, even when a skill declares the connector as required:
     * outside the session every call fails, and the schemas would only be burning context. Listings
     * ({@code ToolDefinitionService}) are unaffected — the catalogue and the ABAC policy editor must
     * see the tools regardless of any session.
     */
    default boolean sessionScopedTools() {
        return false;
    }

    Map<String, Object> executeTool(ConnectorEnv env, String toolName, Map<String, Object> args);
}
