package ru.agimate.controlapi.service.mcp.oauth;

import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.enums.McpOAuthClientKind;

import java.util.List;
import java.util.UUID;

/**
 * What the consent page shows: who asks, where the browser goes back to, and whose agent may answer.
 *
 * @param redirectHost     host the browser returns to; {@code null} for a private-use scheme
 * @param localApplication the browser returns to an application on this computer, not to a website
 */
public record ConsentView(
        String clientId,
        String clientName,
        String clientHost,
        McpOAuthClientKind clientKind,
        String redirectHost,
        boolean localApplication,
        List<AgentChoice> agents
) {

    /** @param available false for an agent whose brain is ours ({@code GENERIC}): it cannot be handed out */
    public record AgentChoice(UUID id, String name, String description, AgentType type, boolean enabled, boolean available) {}
}
