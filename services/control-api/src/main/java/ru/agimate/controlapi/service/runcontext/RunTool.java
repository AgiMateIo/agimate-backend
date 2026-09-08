package ru.agimate.controlapi.service.runcontext;

import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.database.enums.Disclosure;

/**
 * A tool within a run's context: the spec plus the instance's addressing, for routing ExecuteTool,
 * the LLM-facing name and the disclosure axis.
 *
 * @param llmName    the name the model sees and calls ({@code {namespace}.{name}} sanitized, collisions
 *                   suffixed in listing order — {@link ru.agimate.agentworker.ToolNames}); computed here
 *                   rather than at the worker so the same string stands in the listing, in
 *                   {@code load_tools} arguments and in the turn ledger
 * @param disclosure in the catalogue — the declared axis (the tool's override, else the connector's);
 *                   in the assembled context — the wire form: LAZY ships no schema and a
 *                   {@code summary}, EAGER the full spec
 * @param summary    the listing line of a LAZY tool; {@code null} for EAGER
 */
public record RunTool(
        ConnectorToolSpec spec,
        String connectorCode,
        String connectionId,
        String namespace,
        String llmName,
        Disclosure disclosure,
        String summary
) {

    public RunTool eager() {
        return disclosure == Disclosure.EAGER && summary == null
                ? this
                : new RunTool(spec, connectorCode, connectionId, namespace, llmName, Disclosure.EAGER, null);
    }

    public RunTool lazy(String summary) {
        return new RunTool(spec, connectorCode, connectionId, namespace, llmName, Disclosure.LAZY, summary);
    }
}
