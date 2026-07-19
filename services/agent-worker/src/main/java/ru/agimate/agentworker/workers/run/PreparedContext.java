package ru.agimate.agentworker.workers.run;

import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.FilePartRef;
import ru.agimate.agentworker.agent.model.ToolDef;
import ru.agimate.agentworker.agent.ToolRegistry;

import java.util.List;
import java.util.Map;

/**
 * Agent context rendered once before the loop runs — the serializable result of the
 * {@code prepare_context} durable step. The tool registry is carried as its serializable parts
 * ({@code toolDefs} + {@code toolMap}) and reconstructed via {@link #registry()}.
 *
 * <p>{@code inboundParts} added later: an in-flight run's checkpoint predating this field
 * deserializes it as null — the compact constructor normalizes to empty, so no drain is needed.
 *
 * @param systemPrompt        rendered system prompt (ordered blocks with tags)
 * @param userPrompt          rendered persistent part of the user turn (what history keeps)
 * @param ephemeralUserSuffix rendered ephemeral user blocks (memory notes etc.), appended to the
 *                            model-facing turn but never persisted; {@code null} when none
 * @param history             session history «as the user saw it» (completed runs only, mapped
 *                            to user/assistant turns by the backend's kinds)
 * @param inboundParts        inbound attachment refs of this run's user turn (bytes via GetFile)
 */
public record PreparedContext(
        String systemPrompt,
        String userPrompt,
        String ephemeralUserSuffix,
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
