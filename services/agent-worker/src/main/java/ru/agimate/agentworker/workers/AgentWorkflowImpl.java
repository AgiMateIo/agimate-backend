package ru.agimate.agentworker.workers;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.StartWorkflowOptions;
import dev.dbos.transact.workflow.Queue;
import dev.dbos.transact.workflow.StepOptions;
import dev.dbos.transact.workflow.Workflow;
import dev.dbos.transact.workflow.WorkflowClassName;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.GetActiveRunResponse;
import ru.agimate.agentworker.config.AgentProperties;
import ru.agimate.agentworker.dto.AgentMessage;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

/**
 * Router: the {@code agent_runs} entry point. Serializes per session in two layers — an atomic
 * {@code RegisterRun} claim (closes the steer/queue decision race between concurrent routers) and,
 * for the run it enqueues, the partitioned {@code agent_exec} queue (concurrency=1 → one executing
 * run per session). On a busy session it applies the configured policy (queue/steer/interrupt).
 * Stays thin — the run body lives in {@link AgentRunWorkflowImpl}.
 */
@Slf4j
@WorkflowClassName(Queues.AGENT_CLASS)
public class AgentWorkflowImpl implements AgentWorkflow {

    private final DBOS dbos;
    private final AgentWorkerClient client;
    private final AgentRunWorkflow run;
    private final Queue execQueue;
    private final AgentProperties.Session session;

    public AgentWorkflowImpl(DBOS dbos, AgentWorkerClient client, AgentRunWorkflow run, Queue execQueue,
                            AgentProperties.Session session) {
        this.dbos = dbos;
        this.client = client;
        this.run = run;
        this.execQueue = execQueue;
        this.session = session;
    }

    @Override
    @Workflow(name = Queues.AGENT_WORKFLOW)
    public void startAgent(AgentMessage message) {
        String sessionId = SessionSupport.sessionId(message);
        if (sessionId == null) {
            // No session → nothing to serialize; run directly.
            enqueueRun(message, null);
            return;
        }

        // Registry calls are durable steps: the branch decision is checkpointed (a crash-replay
        // must not re-claim a slot the finished run has already released) and transient gRPC
        // errors get step retries instead of failing the workflow.
        // Atomic claim: the partial-unique on RUNNING serializes concurrent routers.
        boolean acquired = dbos.runStep(
                () -> SessionSupport.tryRegister(client, message.agentId(), sessionId, message.runId(),
                        session.getRunTtlSeconds()),
                new StepOptions("register_run").withMaxAttempts(3));
        if (acquired) {
            enqueueRun(message, sessionId);
            return;
        }

        // Busy: another run holds the session slot — find out who (null = holder just finished).
        String holderRunId = dbos.runStep(() -> {
            GetActiveRunResponse active = client.getActiveRun(sessionId);
            return active.getActive() ? active.getActiveRun().getRunId() : null;
        }, new StepOptions("get_active_run").withMaxAttempts(3));
        if (holderRunId == null) {
            enqueueRun(message, sessionId);
            return;
        }
        switch (session.getOnActiveMessage()) {
            case STEER -> steer(holderRunId, message, sessionId);
            case INTERRUPT -> {
                interrupt(holderRunId);
                enqueueRun(message, sessionId);
            }
            case QUEUE -> enqueueRun(message, sessionId);
        }
    }

    /** Enqueue the run stage with {@code workflow_id == run_id}, partitioned by session (else run id). */
    private void enqueueRun(AgentMessage message, String sessionId) {
        String partitionKey = sessionId != null ? sessionId : message.runId();
        dbos.startWorkflow(() -> run.runAgent(message),
                new StartWorkflowOptions(execQueue)
                        .withWorkflowId(message.runId())
                        .withQueuePartitionKey(partitionKey));
        log.debug("enqueued run {} on {} (partition={})", message.runId(), Queues.AGENT_EXEC_QUEUE, partitionKey);
    }

    /**
     * STEER: deliver the new message to the active run's control mailbox. The holder may have
     * passed its final drain (or finished) before the send landed — then the mailbox is never
     * read, so re-check and fall back to a normal run. Residual window: the holder drained the
     * message AND finished between the send and the re-check (duplicate handling) — narrower
     * than the silent loss it replaces.
     */
    private void steer(String holderRunId, AgentMessage message, String sessionId) {
        dbos.send(holderRunId, ControlSignal.steer(message).toJson(), Queues.CONTROL_TOPIC);
        String stillActive = dbos.runStep(() -> {
            GetActiveRunResponse active = client.getActiveRun(sessionId);
            return active.getActive() ? active.getActiveRun().getRunId() : null;
        }, new StepOptions("recheck_active_run").withMaxAttempts(3));
        if (!holderRunId.equals(stillActive)) {
            log.info("steer target {} no longer active; enqueueing message run_id={} as its own run",
                    holderRunId, message.runId());
            enqueueRun(message, sessionId);
            return;
        }
        log.info("steered message run_id={} into active run {}", message.runId(), holderRunId);
    }

    /** INTERRUPT: signal the active run to stop gracefully; the new run is enqueued by the caller. */
    private void interrupt(String holderRunId) {
        dbos.send(holderRunId, ControlSignal.interrupt().toJson(), Queues.CONTROL_TOPIC);
        log.info("sent interrupt to active run {}", holderRunId);
    }
}
