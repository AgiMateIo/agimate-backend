package ru.agimate.controlapi.service.delivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.entities.TriggerLog;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.ToolCallLogRepository;
import ru.agimate.controlapi.service.dto.IToolResult;
import ru.agimate.controlapi.service.trigger.ChannelInfo;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.ChannelsCodec;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerLogService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The persistence half of a detached tool result's delivery: turns the completion into a
 * {@code tool_completed} trigger and a run addressed by provenance — the agent whose call it was,
 * in the session the call came from. No recipient discovery and no ABAC on purpose: routing decides
 * who receives an external event, and here there is nothing to decide. The running main (if any)
 * absorbs the run through steering; an idle session executes it as usual.
 *
 * <p>Enqueueing is left to the caller ({@code AgentDeliveryService}) — this class must not depend
 * on the transport dispatch it is called from.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DetachedToolResultDelivery {

    /**
     * Cap on the output carried in the trigger's data: the in-turn path is capped by the worker
     * ({@code maxOutputChars}), the detached path must not smuggle an unbounded SELECT into
     * {@code trigger_logs.input} and the delivery run's context instead.
     */
    static final int OUTPUT_CAP = 20_000;

    /** Trigger name of a detached completion; {@code connectorCode} stays the tool's connector. */
    public static final String TRIGGER_NAME = "tool_completed";

    private final ToolCallLogRepository toolCallLogRepository;
    private final AgentRunRepository agentRunRepository;
    private final TriggerLogService triggerLogService;

    /** A delivery run ready to enqueue; {@code channels} is null for a direct parent. */
    public record Prepared(AgentRun run, Trigger trigger, Channels channels) {}

    /**
     * Claims the delivery and creates the run; empty when the delivery is not ours to make —
     * already claimed (a duplicate result post), suppressed by the parent run's cancellation, or a
     * call outside a run. The claim and the run share the transaction: a failure to create the run
     * releases the claim.
     */
    @Transactional
    public Optional<Prepared> prepare(ToolCallLog toolCallLog, IToolResult result) {
        if (toolCallLog.getRunId() == null) {
            log.warn("detached call {} has no originating run — nowhere to deliver", toolCallLog.getExternalId());
            return Optional.empty();
        }
        AgentRun parent = agentRunRepository.findById(toolCallLog.getRunId()).orElse(null);
        if (parent == null) {
            log.warn("detached call {}: originating run {} not found", toolCallLog.getExternalId(),
                    toolCallLog.getRunId());
            return Optional.empty();
        }
        if (parent.getCancelRequestedAt() != null) {
            // The user stopped that work; the result stays in tool_call_logs but is not delivered.
            log.info("detached call {} suppressed: run {} was cancelled",
                    toolCallLog.getExternalId(), parent.getId());
            return Optional.empty();
        }
        if (toolCallLogRepository.claimDelivery(toolCallLog.getId(), LocalDateTime.now()) == 0) {
            log.info("detached call {} already delivered — duplicate completion ignored",
                    toolCallLog.getExternalId());
            return Optional.empty();
        }

        Trigger trigger = Trigger.fromSource(
                toolCallLog.getConnectorCode(),
                toolCallLog.getConnectionId(),
                TRIGGER_NAME,
                toolCallLog.getId().toString(),
                triggerData(toolCallLog, result),
                Instant.now());
        TriggerLog triggerLog = triggerLogService.createTriggerLog(toolCallLog.getUserId(), trigger);

        Channels channels = deliveryChannels(ChannelsCodec.fromMap(parent.getChannels()));
        AgentRun run = agentRunRepository.save(AgentRun.builder()
                .triggerLog(triggerLog)
                .agent(parent.getAgent())
                .destination(parent.getAgent().getType().name())
                .sessionId(parent.getSessionId())
                .channels(ChannelsCodec.toMap(channels))
                .build());
        return Optional.of(new Prepared(run, trigger, channels));
    }

    private static Map<String, Object> triggerData(ToolCallLog toolCallLog, IToolResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", toolCallLog.getName());
        data.put("connector", toolCallLog.getConnectorCode());
        data.put("task_id", toolCallLog.getExternalId());
        if (result.getError() != null) {
            data.put("status", "error");
            data.put("error", cap(result.getError()));
        } else {
            data.put("status", "success");
            data.put("output", cap(result.getOutput()));
        }
        return data;
    }

    /**
     * The delivery run keeps the parent's outbound channels but not its prompt: without a prompt
     * channel the context is assembled as SYSTEM_TRIGGER (this is an event, not a user's reply) and
     * the inbound falls back to the compact JSON of the event. The answer slot gets the parent's
     * effective answer channel — {@code answer}, or the {@code prompt} it would have fallen back to.
     */
    private static Channels deliveryChannels(Channels parent) {
        if (parent == null) {
            return null;
        }
        ChannelInfo answer = parent.answer() != null ? parent.answer() : parent.prompt();
        if (answer == null && parent.progress() == null) {
            return null;
        }
        return new Channels(null, parent.progress(), answer);
    }

    private static String cap(String value) {
        if (value == null || value.length() <= OUTPUT_CAP) {
            return value;
        }
        int cut = OUTPUT_CAP;
        if (Character.isHighSurrogate(value.charAt(cut - 1))) {
            cut--;
        }
        return value.substring(0, cut)
                + "…[truncated: " + value.length() + " chars total, first " + cut + " shown]";
    }
}
