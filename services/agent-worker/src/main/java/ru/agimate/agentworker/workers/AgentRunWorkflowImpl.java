package ru.agimate.agentworker.workers;

import dev.dbos.transact.workflow.Workflow;
import dev.dbos.transact.workflow.WorkflowClassName;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import ru.agimate.agentworker.agent.error.AgentRunAborted;
import ru.agimate.agentworker.dto.AgentMessage;
import ru.agimate.agentworker.workers.run.AgentRunCore;
import ru.agimate.agentworker.workers.run.MessageLog;
import ru.agimate.agentworker.workers.run.PreparedContext;

/**
 * The worker's entry point: control-api enqueues this workflow directly onto the partitioned
 * {@code agent_exec} queue ({@code workflow_id == runId}, partition — the run's session), so
 * one run per session executes at a time — single-writer is the queue's contract, no
 * registration handshake. Run lifecycle status is a backend-side projection of this run's
 * {@code SaveMessage} stream. The body is uniform — dialogue vs trigger is server-side policy
 * (ContextSpec), the worker renders blocks and runs the loop via {@link AgentRunCore}.
 */
@Slf4j
@WorkflowClassName(Queues.RUN_CLASS)
public class AgentRunWorkflowImpl implements AgentRunWorkflow {

    private final AgentRunCore core;

    public AgentRunWorkflowImpl(AgentRunCore core) {
        this.core = core;
    }

    @Override
    @Workflow(name = Queues.RUN_WORKFLOW)
    public void runAgent(AgentMessage message) {
        MessageLog messages = core.messageLog(message.agentId(), message.runId());

        // Tag every run-body line with a short run id (the child LLM/tool workflows run on their own
        // threads and won't carry it — that's fine, their lines are DEBUG detail).
        try (MDC.MDCCloseable __ = MDC.putCloseable("run", shortRun(message.runId()))) {
            try {
                runBody(message, messages);
                log.info("run finished");
            } catch (AgentRunAborted e) {
                log.warn(e.systemDetail());
                core.reportFailure(messages, e);
            } catch (Exception e) {
                // Инфра-ошибка (исчерпанные ретраи шага и т.п.): workflow уйдёт в ERROR —
                // терминально, recovery переигрывает только PENDING. Best-effort notice, чтобы
                // пользователь не остался в тишине, затем rethrow — статус ERROR сохраняем.
                core.reportInfraFailure(messages,
                        "agent run infra failure: agent_id=" + message.agentId()
                        + " run=" + message.runId() + ": " + e);
                throw e;
            }
        }
    }

    private void runBody(AgentMessage message, MessageLog messages) {
        log.info("run started: agent={} run={}", message.agentId(), message.runId());

        // Ack «агент получил» — первый диалоговый durable-шаг (seq 0), до сборки контекста:
        // фиксация получения не зависит от успеха prepare_context. На бэке он же переводит
        // статус рана в RUNNING (проекция потока SaveMessage).
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
