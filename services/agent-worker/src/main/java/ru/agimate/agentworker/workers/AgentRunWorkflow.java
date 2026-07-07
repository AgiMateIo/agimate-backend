package ru.agimate.agentworker.workers;

import ru.agimate.agentworker.dto.AgentMessage;

/**
 * One agent run per partitioned {@code agent_exec} queue item; the DBOS workflow id equals
 * {@code run_id}, so steering can address the run.
 */
public interface AgentRunWorkflow {

    void runAgent(AgentMessage message);
}
