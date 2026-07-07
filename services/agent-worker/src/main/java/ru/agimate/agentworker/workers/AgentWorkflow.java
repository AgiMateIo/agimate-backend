package ru.agimate.agentworker.workers;

import ru.agimate.agentworker.dto.AgentMessage;

/** Unified agent orchestrator consuming {@code agent_runs}; routes by the presence of a prompt channel. */
public interface AgentWorkflow {

    void startAgent(AgentMessage message);
}
