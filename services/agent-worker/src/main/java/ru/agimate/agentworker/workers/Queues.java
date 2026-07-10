package ru.agimate.agentworker.workers;

import ru.agimate.agentworker.WorkerProtocol;

/**
 * Queue names and producer-contract identifiers. The entry-point names come from the shared
 * {@link WorkerProtocol} (compiled into control-api too, so producer and consumer cannot drift);
 * the {@code agent_exec}/{@code llm_calls}/{@code tool_calls} queues are internal to the worker
 * (it both enqueues and consumes them).
 */
public final class Queues {

    private Queues() {
    }

    // Producer contract — shared with control-api via WorkerProtocol.
    public static final String AGENT_QUEUE = WorkerProtocol.AGENT_QUEUE;
    public static final String AGENT_CLASS = WorkerProtocol.AGENT_CLASS;
    public static final String AGENT_WORKFLOW = WorkerProtocol.AGENT_WORKFLOW;
    public static final String INSTANCE = WorkerProtocol.INSTANCE;

    // Internal run-stage queue: partitioned by session, concurrency=1 → one writer per session.
    public static final String AGENT_EXEC_QUEUE = "agent_exec";
    public static final String RUN_CLASS = "AgentRunWorkflow";
    public static final String RUN_WORKFLOW = "run_agent";


    // Internal queues splitting LLM traffic from tool traffic.
    public static final String LLM_QUEUE = "llm_calls";
    public static final String TOOL_QUEUE = "tool_calls";
    public static final String LLM_WORKFLOW = "llm_call";
    public static final String TOOL_WORKFLOW = "tool_call";
    public static final String LLM_CLASS = "LlmCallWorkflow";
    public static final String TOOL_CLASS = "ToolCallWorkflow";
}
