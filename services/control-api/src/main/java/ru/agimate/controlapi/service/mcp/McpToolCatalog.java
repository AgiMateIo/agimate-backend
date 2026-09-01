package ru.agimate.controlapi.service.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.abac.ConnectionAccessEvaluator;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.enums.PolicyKind;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.service.tool.ToolDefinitionService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What an MCP client sees and what a name it calls means. An agent's tools, addressed by instance:
 * every active binding ({@code agent_connections}) contributes the tools of its connection, minus
 * what ABAC denies — the same rule the agent itself lives under, so a client never gets more than
 * the agent has.
 *
 * <p>It is not {@code AgentService.getAvailableTools}: that one flattens names across connections for
 * an agent that already knows its instances, while a call arriving over MCP carries nothing but a
 * name and has to be routed back to one connection.
 *
 * <p>The catalog is rebuilt per request. Stateless MCP has no session to hang a cache on, and the
 * expensive part (the policy decisions) is already cached inside {@link ConnectionAccessEvaluator}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class McpToolCatalog {

    /**
     * Separator between the instance handle and the tool name. Two underscores, because a single one
     * occurs inside {@code full_code} itself ({@code mcp_context7}) and would make the halves
     * ambiguous to a human reading the listing.
     */
    private static final String SEPARATOR = "__";

    /**
     * Clients pass tool names into function-calling APIs, which cap them at 64 characters; a longer
     * name is truncated with a hash tail rather than dropped, so it stays unique and stable.
     */
    private static final int MAX_NAME_LENGTH = 64;

    private final AgentConnectionRepository agentConnectionRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectorRepository connectorRepository;
    private final ToolDefinitionService toolDefinitionService;
    private final ConnectionAccessEvaluator accessEvaluator;
    private final ConnectorRegistry connectorRegistry;

    /** One tool of one connection, under the name the client sees. */
    public record ToolEntry(UUID connectionId, String connectorCode, String toolName, ConnectorToolSpec spec) {}

    public Map<String, ToolEntry> forAgent(Agent agent) {
        Map<String, ToolEntry> catalog = new LinkedHashMap<>();
        for (AgentConnection binding : agentConnectionRepository.findActiveByAgentId(agent.getId())) {
            Connection connection = connectionRepository.findByIdNotDeleted(binding.getConnectionId()).orElse(null);
            if (connection == null || !connection.isUsable()) {
                continue;
            }
            Connector connector = connectorRepository.findById(connection.getConnectorCode()).orElse(null);
            // No definitionBinding means the connector exposes no tools at all (a pure channel) — not an error here.
            if (connector == null || connector.getDefinitionBinding() == null) {
                continue;
            }
            addTools(catalog, agent, connection);
        }
        return catalog;
    }

    private void addTools(Map<String, ToolEntry> catalog, Agent agent, Connection connection) {
        Map<String, ConnectorToolSpec> tools = toolDefinitionService.getTools(
                agent.getUserId(), connection.getConnectorCode(), connection.getId());

        // The same handle the worker uses ({@code RunContextService.namespaceOf}): an internal mode
        // row is named by its connector code, an external instance by its {@code full_code}. Aligning
        // the two surfaces matters for long tool names — a 36-char uuid handle plus a 64-char cap
        // would truncate names like {@code list_llm_provider_models} into hash tails.
        String handle = connectorRegistry.findHandler(connection.getConnectorCode())
                .map(InternalConnectorHandler.class::isInstance)
                .orElse(false)
                ? connection.getConnectorCode()
                : connection.getFullCode();

        tools.forEach((toolName, spec) -> {
            if (!accessEvaluator.evaluate(agent.getId(), connection.getId(), PolicyKind.TOOL, toolName).allowed()) {
                return;
            }
            String publicName = publicName(handle, toolName);
            ToolEntry entry = new ToolEntry(connection.getId(), connection.getConnectorCode(), toolName, spec);
            ToolEntry clashing = catalog.putIfAbsent(publicName, entry);
            if (clashing != null) {
                // Two names that differ only in characters MCP does not allow. Dropping silently would read
                // as «the agent has no such tool»; the owner needs to know which one is unreachable.
                log.warn("MCP tool name '{}' is taken by {}.{} — {}.{} is not listed for agent {}",
                        publicName, clashing.connectorCode(), clashing.toolName(),
                        connection.getConnectorCode(), toolName, agent.getId());
            }
        });
    }

    /**
     * {@code <full_code>__<tool>}, reduced to what function-calling APIs accept
     * ({@code [a-zA-Z0-9_-]}). The mapping is one-way on purpose: a call is resolved through the
     * catalog, never by taking the name apart.
     */
    private static String publicName(String fullCode, String toolName) {
        String name = sanitize(fullCode) + SEPARATOR + sanitize(toolName);
        if (name.length() <= MAX_NAME_LENGTH) {
            return name;
        }
        String tail = Integer.toHexString(name.hashCode());
        return name.substring(0, MAX_NAME_LENGTH - tail.length() - 1) + "_" + tail;
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
