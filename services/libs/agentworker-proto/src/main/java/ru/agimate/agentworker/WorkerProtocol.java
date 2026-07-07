package ru.agimate.agentworker;

/**
 * Producer↔worker DBOS contract: the queue/class/workflow/instance names control-api enqueues
 * with and the worker registers under, plus the workflow-id scheme. Compiled into both services
 * so the contract cannot drift. Deliberately code, not configuration — changing any value is a
 * coordinated two-sided deploy (in-flight queue items break otherwise).
 */
public final class WorkerProtocol {

    private WorkerProtocol() {
    }

    /** Entry queue the producer enqueues agent runs onto. */
    public static final String AGENT_QUEUE = "agent_runs";
    public static final String AGENT_CLASS = "AgentWorkflow";
    public static final String AGENT_WORKFLOW = "start_agent";
    public static final String INSTANCE = "default";

    /**
     * The bare {@code runId} is reserved for the worker's run-stage workflow ({@code run_id} ==
     * its DBOS workflow id — the address steering and the run registry use), so the router entry
     * workflow is enqueued under a derived id.
     */
    public static final String ROUTER_ID_SUFFIX = ":router";

    /** DBOS workflow id of the router entry workflow for a run. */
    public static String routerWorkflowId(String runId) {
        return runId + ROUTER_ID_SUFFIX;
    }
}
