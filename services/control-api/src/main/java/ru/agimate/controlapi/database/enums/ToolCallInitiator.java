package ru.agimate.controlapi.database.enums;

/**
 * Who started a tool call on the agent's behalf. Every call runs as the agent, so {@code agent_id} alone
 * cannot tell the owner «the agent did it» from «I pressed a button in its panel».
 */
public enum ToolCallInitiator {
    /** The agent's model: a run on the worker, an external agent or an MCP client, all of them a model. */
    AGENT,
    /** The owner, from a view of the agent's connection (docs/decisions/connector-views.md). */
    VIEW
}
