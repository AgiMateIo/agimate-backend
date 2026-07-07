package ru.agimate.agentworker.workers;

/**
 * Queue names and producer-contract identifiers. The {@code agent_runs} queue plus the
 * {@code AgentWorkflow}/{@code start_agent}/{@code default} names must match what control-api
 * enqueues ({@code DbosDeliveryService}); the {@code llm_calls}/{@code tool_calls} queues are
 * internal to the worker (it both enqueues and consumes them).
 */
public final class Queues {

    private Queues() {
    }

    // Producer contract — must match control-api's EnqueueOptions.
    public static final String AGENT_QUEUE = "agent_runs";
    public static final String AGENT_CLASS = "AgentWorkflow";
    public static final String AGENT_WORKFLOW = "start_agent";
    public static final String INSTANCE = "default";

    // Internal run-stage queue: partitioned by session, concurrency=1 → one writer per session.
    public static final String AGENT_EXEC_QUEUE = "agent_exec";
    public static final String RUN_CLASS = "AgentRunWorkflow";
    public static final String RUN_WORKFLOW = "run_agent";

    // DBOS mailbox topic for steering signals delivered to an active run.
    public static final String CONTROL_TOPIC = "control";

    // Internal queues splitting LLM traffic from tool traffic.
    public static final String LLM_QUEUE = "llm_calls";
    public static final String TOOL_QUEUE = "tool_calls";
    public static final String LLM_WORKFLOW = "llm_call";
    public static final String TOOL_WORKFLOW = "tool_call";
    public static final String LLM_CLASS = "LlmCallWorkflow";
    public static final String TOOL_CLASS = "ToolCallWorkflow";
}
