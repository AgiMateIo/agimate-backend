package ru.agimate.controlapi.service.mcp.oauth;

import ru.agimate.controlapi.database.entities.Agent;

import java.util.UUID;

/** A valid access token on {@code /mcp}: the agent it acts as and the grant it belongs to. */
public record McpAccess(Agent agent, UUID grantId) {}
