package ru.agimate.agentworker.workers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dbos.transact.DBOS;
import dev.dbos.transact.workflow.StepOptions;
import dev.dbos.transact.workflow.Workflow;
import dev.dbos.transact.workflow.WorkflowClassName;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.error.AgentInterrupted;
import ru.agimate.agentworker.agent.error.AgentRunAborted;
import ru.agimate.agentworker.workers.run.AgentRunCore;
import ru.agimate.agentworker.workers.run.OutboundPublisher;
import ru.agimate.agentworker.workers.run.PreparedContext;
import ru.agimate.agentworker.workers.run.SessionBinding;
import ru.agimate.agentworker.agent.PromptBuilder;
import ru.agimate.agentworker.config.AgentProperties;
import ru.agimate.agentworker.dto.AgentMessage;
import ru.agimate.agentworker.dto.ChannelInfo;
import ru.agimate.agentworker.dto.Trigger;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Run stage: the invariant agent-run body, consumed from the partitioned {@code agent_exec} queue
 * (one writer per session). Registers the session slot at start (idempotent by its own run id;
 * ABORTED → abnormal, exit) and releases it in {@code finally}. Routes channel vs trigger and runs
 * the loop via {@link AgentRunCore}.
 */
@Slf4j
@WorkflowClassName(Queues.RUN_CLASS)
public class AgentRunWorkflowImpl implements AgentRunWorkflow {

    private static final ObjectMapper DATA_MAPPER = new ObjectMapper();

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
        String sessionId = SessionSupport.sessionId(message);
        OutboundPublisher output = new OutboundPublisher(client, message.agentId(), message.channels(), message.runId());

        // Durable step: idempotent re-affirm on replay (checkpointed result) + retries on
        // transient gRPC errors. ABORTED (slot held by another run) comes back as false.
        boolean acquired = sessionId == null || dbos.runStep(
                () -> SessionSupport.tryRegister(client, message.agentId(), sessionId, message.runId(),
                        session.getRunTtlSeconds()),
                new StepOptions("register_run").withMaxAttempts(3));
        if (!acquired) {
            // Anomaly: the partition queue serialized us behind the holder, yet the slot is still
            // taken — the previous run died without releasing (slot frees on TTL). Report instead
            // of dropping silently: the user gets a notice, the backend gets the detail.
            log.warn("session {} already has an active run; aborting run {}", sessionId, message.runId());
            core.reportFailure(output, new AgentRunAborted(BUSY_NOTICE,
                    "session " + sessionId + " slot is held by another run; dropping run " + message.runId()));
            return;
        }

        try {
            if (message.promptChannel() != null) {
                runChannel(message, output);
            } else {
                runTrigger(message, output);
            }
        } catch (AgentRunAborted e) {
            log.warn(e.systemDetail());
            core.reportFailure(output, e);
        } catch (AgentInterrupted e) {
            log.info("run {} interrupted by steering; releasing without a final answer", message.runId());
        } finally {
            if (sessionId != null) {
                try {
                    // Durable step: retried on transient gRPC errors so the slot rarely leaks to TTL.
                    dbos.runStep(() -> client.releaseRun(sessionId, message.runId()).getReleased(),
                            new StepOptions("release_run").withMaxAttempts(3));
                } catch (Exception e) {
                    log.warn("releaseRun failed for run {} (TTL will reclaim): {}", message.runId(), e.getMessage());
                }
            }
        }
    }

    private void runChannel(AgentMessage message, OutboundPublisher output) {
        ChannelInfo promptCh = message.promptChannel();
        if (message.inbound() == null || message.inbound().text() == null || message.inbound().text().isEmpty()) {
            throw new AgentRunAborted("", "channel message requires inbound.text (run_id="
                    + message.runId() + " agent_id=" + message.agentId() + ")");
        }
        String prompt = message.inbound().text();
        log.info("run_agent channel: run_id={} agent_id={} channel={}", message.runId(), message.agentId(),
                promptCh.channelId());

        PreparedContext prepared = core.prepareContext(message.agentId(), null);
        core.run(message.agentId(), prepared, AgentChatMessage.user(prompt),
                sessionBinding(message, prompt), output,
                "for agent_id=" + message.agentId() + " channel=" + promptCh.channelId(), drainControl());
    }

    private void runTrigger(AgentMessage message, OutboundPublisher output) {
        Trigger payload = message.payload();
        log.info("run_agent trigger: run_id={} agent_id={} connector={} name={}", message.runId(),
                message.agentId(), payload.connectorCode(), payload.name());

        PreparedContext prepared = core.prepareContext(message.agentId(), List.of(payload));
        String request = PromptBuilder.buildUntrustedTriggerRequest(payload);
        String answer = core.run(message.agentId(), prepared, AgentChatMessage.user(request),
                sessionBinding(message, payload.name()), output,
                "for agent_id=" + message.agentId() + " trigger=" + payload.name(), drainControl());
        log.info("trigger handled for agent_id={} event={}; answer: {}", message.agentId(), payload.name(), answer);
    }

    /** Drain the control mailbox only when the session policy actually delivers steering signals. */
    private boolean drainControl() {
        return session.getOnActiveMessage() != AgentProperties.Session.OnActiveMessage.QUEUE;
    }

    private SessionBinding sessionBinding(AgentMessage message, String initialText) {
        String sessionId = SessionSupport.sessionId(message);
        if (sessionId == null) {
            return null;
        }
        return new SessionBinding(sessionId, message.runId(), initialText, triggerInputJson(message.payload()));
    }

    private static byte[] triggerInputJson(Trigger payload) {
        try {
            return DATA_MAPPER.writeValueAsBytes(payload != null ? payload.data() : Map.of());
        } catch (Exception e) {
            return "{}".getBytes(StandardCharsets.UTF_8);
        }
    }
}
