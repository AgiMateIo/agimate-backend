package ru.agimate.agentworker.workers.run;

import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.FilePartRef;
import ru.agimate.agentworker.agent.model.ToolDef;
import ru.agimate.agentworker.agent.ToolRegistry;

import java.util.List;
import java.util.Map;

/**
 * Agent context rendered once before the loop runs and held in memory for its duration — never
 * checkpointed (see {@code AgentRunCore.prepareContext}). The tool registry is carried as its
 * parts ({@code toolDefs} + {@code toolMap}) and reconstructed via {@link #registry()}.
 *
 * @param systemPrompt        rendered system prompt (ordered blocks with tags)
 * @param userPrompt          rendered persistent part of the user turn (what history keeps)
 * @param ephemeralUserPrefix rendered ephemeral user blocks (memory notes etc.), prepended to the
 *                            model-facing turn but never persisted; {@code null} when none.
 * @param history             session history «as the user saw it» (completed runs only, mapped
 *                            to user/assistant turns by the backend's kinds)
 * @param inboundParts        inbound attachment refs of this run's user turn (bytes via GetFile)
 */
public record PreparedContext(
        String systemPrompt,
        String userPrompt,
        String ephemeralUserPrefix,
        List<AgentChatMessage> history,
        List<ToolDef> toolDefs,
        Map<String, ToolRegistry.BackendTool> toolMap,
        List<FilePartRef> inboundParts) {

    public PreparedContext {
        inboundParts = inboundParts != null ? inboundParts : List.of();
    }

    public ToolRegistry registry() {
        return ToolRegistry.of(toolDefs, toolMap);
    }
}
