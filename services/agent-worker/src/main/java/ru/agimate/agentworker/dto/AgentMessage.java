package ru.agimate.agentworker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The DBOS payload that starts a run (protocol v2): addressing only — the agent and the run
 * (agent_runs.id == the DBOS workflow id). Everything else (prompt blocks, tools, history, channels)
 * the worker fetches with a single {@code GetRunContext(agent_id, run_id)}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentMessage(String agentId, String runId) {
}
