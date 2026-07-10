package ru.agimate.agentworker.agent.context;

import ru.agimate.agentworker.ConnectorToolSpec;
import ru.agimate.agentworker.HistoryMessage;
import ru.agimate.agentworker.PromptBlock;

import java.util.List;

/**
 * Raw materials for one context assembly — the {@code GetRunContext} payload as fetched, nothing
 * about how to render it. The backend owns the assembly policy (block scoping, ordering, trust
 * flags, history window/filter); {@link ContextBuilder} only renders what arrives here.
 *
 * @param systemBlocks ordered system-prompt blocks (stable-first — the order is the contract)
 * @param userBlocks   ordered user-turn blocks; the run's main prompt is the last one
 * @param tools        tool specs, already scoped and ABAC-gated by the backend
 * @param history      session history «as the user saw it» (completed runs only, pre-filtered)
 */
public record ContextMaterials(
        List<PromptBlock> systemBlocks,
        List<PromptBlock> userBlocks,
        List<ConnectorToolSpec> tools,
        List<HistoryMessage> history) {
}
