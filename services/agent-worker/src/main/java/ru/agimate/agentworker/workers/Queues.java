package ru.agimate.agentworker.workers;

import ru.agimate.agentworker.WorkerProtocol;

/**
 * Queue names and producer-contract identifiers. The run-stage entry point comes from the shared
 * {@link WorkerProtocol} (compiled into control-api too, so producer and consumer cannot drift);
 * the {@code llm_calls}/{@code tool_calls} queues are internal to the worker (it both enqueues
 * and consumes them).
 */
public final class Queues {

    private Queues() {
    }

    // Producer contract — control-api enqueues runs directly onto the partitioned run queue.
    public static final String RUN_QUEUE = WorkerProtocol.RUN_QUEUE;
    public static final String RUN_CLASS = WorkerProtocol.RUN_CLASS;
    public static final String RUN_WORKFLOW = WorkerProtocol.RUN_WORKFLOW;
    public static final String INSTANCE = WorkerProtocol.INSTANCE;

    // Internal queues splitting LLM traffic from tool traffic.
    public static final String LLM_QUEUE = "llm_calls";
    public static final String TOOL_QUEUE = "tool_calls";
    public static final String LLM_WORKFLOW = "llm_call";
    public static final String TOOL_WORKFLOW = "tool_call";
    public static final String LLM_CLASS = "LlmCallWorkflow";
    public static final String TOOL_CLASS = "ToolCallWorkflow";
}
