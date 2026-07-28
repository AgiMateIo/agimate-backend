package ru.agimate.controlapi.service.runcontext;

import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;

/**
 * A tool within a run's context: the spec plus the instance's addressing, for routing ExecuteTool and
 * for building the LLM-facing name ({@code {namespace}.{name}}) at the worker.
 */
public record RunTool(
        ConnectorToolSpec spec,
        String connectorCode,
        String connectionId,
        String namespace
) {
}
