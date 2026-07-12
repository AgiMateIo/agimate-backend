package ru.agimate.agentworker;

/**
 * Producer↔worker DBOS contract: the queue/class/workflow/instance names control-api enqueues
 * with and the worker registers under. Compiled into both services so the contract cannot
 * drift. Deliberately code, not configuration — changing any value is a coordinated two-sided
 * deploy (in-flight queue items break otherwise).
 *
 * <p>The producer enqueues the run-stage workflow directly: {@code workflow_id == runId}
 * (= {@code trigger_log_agents.id}), partition key — the run's {@code sessionId} (or the
 * {@code runId} itself for a direct run). The partitioned queue (concurrency=1 per partition)
 * is the single-writer-per-session boundary — a contract requirement on the transport.
 */
public final class WorkerProtocol {

    private WorkerProtocol() {
    }

    /** Entry queue the producer enqueues agent runs onto (partitioned by session). */
    public static final String RUN_QUEUE = "agent_exec";
    public static final String RUN_CLASS = "AgentRunWorkflow";
    public static final String RUN_WORKFLOW = "run_agent";
    public static final String INSTANCE = "default";
}
