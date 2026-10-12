package ru.agimate.controlapi.service.mcp.oauth;

import ru.agimate.controlapi.database.enums.McpOAuthClientKind;

import java.time.LocalDateTime;
import java.util.UUID;

/** A connected client of an agent, as the agent's card lists it. */
public record McpGrantView(
        UUID id,
        String clientId,
        String clientName,
        McpOAuthClientKind clientKind,
        LocalDateTime createdAt,
        LocalDateTime lastUsedAt
) {}
