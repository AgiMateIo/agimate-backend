package ru.agimate.controlapi.service.runcontext;

import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;

/**
 * A tool within a run's context: the spec plus the instance's addressing, for routing ExecuteTool,
 * and the LLM-facing name.
 *
 * @param llmName the name the model sees and calls ({@code {namespace}.{name}} sanitized, collisions
 *                suffixed in listing order — {@link ru.agimate.agentworker.ToolNames}); computed here
 *                rather than at the worker so the same string stands in the listing, in
 *                {@code load_tools} arguments and in the turn ledger
 */
public record RunTool(
        ConnectorToolSpec spec,
        String connectorCode,
        String connectionId,
        String namespace,
        String llmName
) {
}
