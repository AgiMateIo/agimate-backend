package ru.agimate.agentworker.workers;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.workflow.StepOptions;
import dev.dbos.transact.workflow.Workflow;
import dev.dbos.transact.workflow.WorkflowClassName;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import ru.agimate.agentworker.agent.error.AgentRunAborted;
import ru.agimate.agentworker.config.AgentProperties;
import ru.agimate.agentworker.dto.AgentMessage;
import ru.agimate.agentworker.grpc.AgentWorkerClient;
import ru.agimate.agentworker.workers.run.AgentRunCore;
import ru.agimate.agentworker.workers.run.MessageLog;
import ru.agimate.agentworker.workers.run.PreparedContext;

/**
 * Run stage: the invariant agent-run body, consumed from the partitioned {@code agent_exec} queue
 * (one writer per session). Re-affirms the session slot at start (idempotent by its own run id;
 * BUSY → anomaly, report and exit) and releases it in {@code finally}. The body is uniform —
 * dialogue vs trigger is server-side policy (ContextSpec), the worker renders blocks and runs the
 * loop via {@link AgentRunCore}.
 */
@Slf4j
@WorkflowClassName(Queues.RUN_CLASS)
public class AgentRunWorkflowImpl implements AgentRunWorkflow {

    /** User notice when the session slot is stuck behind a run that never released (TTL backstop). */
    static final String BUSY_NOTICE =
            "Извини, не получилось обработать сообщение — предыдущий запуск ещё не завершён. "
            + "Попробуй ещё раз чуть позже.";

    private final DBOS dbos;
    private final AgentWorkerClient client;
    private final AgentRunCore core;
    private final AgentProperties.Session session;

    public AgentRunWorkflowImpl(DBOS dbos, AgentWorkerClient client, AgentRunCore core,
                                AgentProperties.Session session) {
        this.dbos = dbos;
        this.client = client;
        this.core = core;
        this.session = session;
    }

    @Override
    @Workflow(name = Queues.RUN_WORKFLOW)
    public void runAgent(AgentMessage message) {
        MessageLog messages = core.messageLog(message.agentId(), message.runId());

        // Tag every run-body line with a short run id (the child LLM/tool workflows run on their own
        // threads and won't carry it — that's fine, their lines are DEBUG detail).
        try (MDC.MDCCloseable __ = MDC.putCloseable("run", shortRun(message.runId()))) {
            // Durable step: idempotent re-affirm on replay (checkpointed result) + retries on
            // transient gRPC errors. BUSY — the slot is held by another run.
            SlotClaim slot = dbos.runStep(
                    () -> SlotClaim.from(client.registerRun(
                            message.agentId(), message.runId(), session.getRunTtlSeconds())),
                    new StepOptions("register_run").withMaxAttempts(3));
            if (slot.busy()) {
                // Anomaly: the partition queue serialized us behind the holder, yet the slot is still
                // taken — the previous run died without releasing (slot frees on TTL). Report instead
                // of dropping silently: the user gets a notice, the backend gets the detail.
                log.warn("session slot busy; aborting run {}", message.runId());
                core.reportFailure(messages, new AgentRunAborted(BUSY_NOTICE,
                        "session slot is held by another run; dropping run " + message.runId()));
                return;
            }
            boolean hasSession = slot.acquired();

            try {
                runBody(message, messages);
                log.info("run finished");
            } catch (AgentRunAborted e) {
                log.warn(e.systemDetail());
                core.reportFailure(messages, e);
            } finally {
                if (hasSession) {
                    try {
                        // Durable step: retried on transient gRPC errors so the slot rarely leaks to TTL.
                        dbos.runStep(() -> client.releaseRun(message.agentId(), message.runId()).getReleased(),
                                new StepOptions("release_run").withMaxAttempts(3));
                    } catch (Exception e) {
                        log.warn("releaseRun failed (TTL will reclaim): {}", e.getMessage());
                    }
                }
            }
        }
    }

    private void runBody(AgentMessage message, MessageLog messages) {
        log.info("run started: agent={} run={}", message.agentId(), message.runId());

        // Ack «агент получил» — первый диалоговый durable-шаг (seq 0), до сборки контекста:
        // фиксация получения не зависит от успеха prepare_context.
        messages.inbound();

        PreparedContext prepared = core.prepareContext(message.agentId(), message.runId());
        core.run(message.agentId(), message.runId(), prepared, messages,
                "for agent_id=" + message.agentId() + " run=" + message.runId());
    }

    /**
     * Last 8 hex of the run's UUID — enough to correlate a run's lines in the console. Taken from the
     * tail (random {@code rand_b}), not the head: run ids are UUIDv7, whose leading hex encode the
     * millisecond clock and stay identical for runs within the same ~65s window.
     */
    private static String shortRun(String runId) {
        return runId != null && runId.length() >= 8 ? runId.substring(runId.length() - 8) : runId;
    }
}
