package ru.agimate.agentworker.agent.context;

import ru.agimate.agentworker.agent.ToolRegistry;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.FilePartRef;

import java.util.List;

/**
 * The run context rendered once before the loop and held in memory for its duration — never
 * checkpointed (see {@code AgentRunCore.prepareContext}).
 *
 * @param systemPrompt        rendered system prompt (ordered blocks with tags)
 * @param userPrompt          rendered persistent part of the user turn (what the ledger keeps)
 * @param ephemeralUserPrefix rendered ephemeral user blocks (memory notes etc.), prepended to the
 *                            model-facing turn only; {@code null} when none
 * @param history             session history from the turn ledger, as model turns
 * @param inboundParts        inbound attachment refs of this run's user turn (bytes via GetFile)
 */
public record PreparedContext(
        String systemPrompt,
        String userPrompt,
        String ephemeralUserPrefix,
        List<AgentChatMessage> history,
        ToolRegistry registry,
        List<FilePartRef> inboundParts) {

    public PreparedContext {
        inboundParts = inboundParts != null ? inboundParts : List.of();
    }
}
