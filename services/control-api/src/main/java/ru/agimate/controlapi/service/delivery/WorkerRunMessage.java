package ru.agimate.controlapi.service.delivery;

/**
 * The DBOS payload that starts a run (protocol v2): addressing only — the agent and the run
 * (agent_runs.id == the DBOS workflow id). Everything else (prompt blocks, tools, history, channels)
 * the worker fetches with a single {@code GetRunContext(agent_id, trigger_id)}.
 */
public record WorkerRunMessage(String agentId, String runId) {
}
