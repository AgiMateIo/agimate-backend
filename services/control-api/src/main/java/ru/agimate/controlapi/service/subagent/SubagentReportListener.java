package ru.agimate.controlapi.service.subagent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.agimate.controlapi.service.AgentDeliveryService;
import ru.agimate.controlapi.service.runcontext.RunCatalog;
import ru.agimate.controlapi.service.team.AgentRequestEventPublisher;
import ru.agimate.controlapi.service.trigger.RunActivityService;
import ru.agimate.controlapi.service.trigger.RunsSwept;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.UUID;

/**
 * The two ways a child run ends: it says its last word into its channel ({@link ChildOutput}),
 * or it dies silently and the stale-run sweeper marks it failed ({@link RunsSwept}). Both become the
 * same report; the claim in {@link SubagentReportDelivery} keeps it to one.
 *
 * <p>No job and no timer of its own: the sweeper already decides that a run is dead, and a report is
 * owed exactly then. At rest the connector writes nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubagentReportListener {

    private final SubagentReportDelivery reportDelivery;
    private final AgentDeliveryService agentDeliveryService;
    private final AgentRequestEventPublisher eventPublisher;

    /** Synchronous: published from the message log's delivery, which already runs after its commit. */
    @EventListener
    public void onOutput(ChildOutput output) {
        report(output.runId(), output.failed(), output.text());
    }

    @TransactionalEventListener
    public void onSwept(RunsSwept swept) {
        for (UUID runId : swept.runIds()) {
            report(runId, true, RunActivityService.STALE_ERROR);
        }
    }

    private void report(UUID childRunId, boolean failed, String text) {
        try {
            reportDelivery.prepare(childRunId, failed, text).ifPresent(prepared -> {
                agentDeliveryService.deliverTrigger(prepared.run(), prepared.trigger(), prepared.channels(), null);
                publishRequestEvent(prepared.trigger());
            });
        } catch (Exception e) {
            log.error("report of child run {} was not delivered: {}", childRunId, e.getMessage(), e);
        }
    }

    /**
     * A thread of another agent is also a row of the team's requests, and the screen showing them is
     * watching. A subagent's report is nobody's row — its place is beside the conversation.
     */
    private void publishRequestEvent(Trigger trigger) {
        if (!RunCatalog.AGENTS.equals(trigger.connectorCode())) {
            return;
        }
        Object threadId = trigger.data().get("threadId");
        if (threadId != null) {
            eventPublisher.publish(UUID.fromString(threadId.toString()), AgentRequestEventPublisher.REPORTED);
        }
    }
}
