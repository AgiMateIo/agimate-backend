package ru.agimate.agentworker.workers;

import dev.dbos.transact.workflow.Workflow;
import dev.dbos.transact.workflow.WorkflowClassName;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import ru.agimate.agentworker.dto.AgentMessage;
import ru.agimate.agentworker.workers.run.AgentRunCore;

/**
 * The worker's entry point: control-api enqueues this workflow directly onto the partitioned
 * {@code agent_exec} queue ({@code workflow_id == runId}, partition — the run's session), so
 * one run per session executes at a time — single-writer is the queue's contract, no
 * registration handshake. Run lifecycle status is a backend-side projection of this run's
 * {@code SaveMessage} stream. This class keeps only what the DBOS surface needs — the workflow
 * annotation and the log tag on the run's thread; the run itself is {@link AgentRunCore#run}.
 */
@Slf4j
@WorkflowClassName(Queues.RUN_CLASS)
public class AgentRunWorkflowImpl implements AgentRunWorkflow {

    private final AgentRunCore agentRunCore;

    public AgentRunWorkflowImpl(AgentRunCore agentRunCore) {
        this.agentRunCore = agentRunCore;
    }

    @Override
    @Workflow(name = Queues.RUN_WORKFLOW)
    public void runAgent(AgentMessage message) {
        // Tag every line of the run with a short run id — model and tool steps run on this thread too.
        try (MDC.MDCCloseable __ = MDC.putCloseable("run", shortRun(message.runId()))) {
            agentRunCore.run(message);
        }
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
