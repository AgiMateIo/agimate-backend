package ru.agimate.agentworker.workers;

import ru.agimate.agentworker.dto.AgentMessage;

/**
 * Run stage: the actual agent run, consumed from the partitioned {@code agent_exec} queue
 * (concurrency=1 per session → one writer per session). Its DBOS workflow id equals {@code run_id}
 * so steering can address it. Registers the session slot at start and releases it in finally.
 */
public interface AgentRunWorkflow {

    void runAgent(AgentMessage message);
}
