package ru.agimate.controlapi.database.enums;

/**
 * Where the agent's brain lives — and therefore how work reaches it: the delivery transport is
 * looked up by this value ({@code AgentTransport}), and so is the surface its key opens
 * ({@code AgentAuthFilter}). An agent has exactly one brain, so the values are exclusive.
 *
 * <p>{@link #MCP} is the only one with no push at all: stateless MCP has no server→client channel,
 * so such an agent comes for its tools itself and is never a trigger recipient.
 */
public enum AgentType {
    CENTRIFUGO,
    WEBHOOK,
    GENERIC,
    MCP
}
