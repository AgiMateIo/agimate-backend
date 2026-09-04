package ru.agimate.agentworker.workers;

import ru.agimate.agentworker.WorkerProtocol;

/**
 * Producer-contract identifiers, from the shared {@link WorkerProtocol} (compiled into control-api
 * too, so producer and consumer cannot drift). The run workflow is the worker's only workflow:
 * model requests and tool calls are its durable steps, not queued children.
 */
public final class Queues {

    private Queues() {
    }

    public static final String RUN_QUEUE = WorkerProtocol.RUN_QUEUE;
    public static final String RUN_CLASS = WorkerProtocol.RUN_CLASS;
    public static final String RUN_WORKFLOW = WorkerProtocol.RUN_WORKFLOW;
    public static final String INSTANCE = WorkerProtocol.INSTANCE;
}
