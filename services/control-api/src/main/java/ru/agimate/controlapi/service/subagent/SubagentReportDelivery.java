package ru.agimate.controlapi.service.subagent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.TriggerLog;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.service.trigger.ChannelInfo;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.ChannelsCodec;
import ru.agimate.controlapi.service.trigger.RunActivityService;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerLogService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The persistence half of a subagent's report: turns the end of a subagent run into a
 * {@code subagents.report_received} trigger and a run of the conversation the subagent works for —
 * by provenance, like a detached tool's result, with no recipient discovery. Enqueueing is the
 * caller's.
 *
 * <p>Every report run answers where the run that asked answered; {@code remaining} in the data tells the
 * agent whether to note progress or bring the reports together. No history-only slot for the reports
 * that leave others working: steering absorbs the younger runs of the same session into the running
 * one, and a run answering into history would take a person's message or the last report with it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubagentReportDelivery {

    /** Cap on the report carried in the trigger's data — the same bound as a detached tool's output. */
    static final int REPORT_CAP = 20_000;

    /** Bound on the origin chain inside one subagent session: each detached call that answers adds a hop. */
    static final int MAX_ORIGIN_HOPS = 32;

    private final AgentRunRepository agentRunRepository;
    private final AgentSessionRepository agentSessionRepository;
    private final TriggerLogService triggerLogService;
    private final SubagentService subagentService;

    /** A report run ready to enqueue. */
    public record Prepared(AgentRun run, Trigger trigger, Channels channels) {}

    /**
     * Claims the report of {@code childRunId} and creates the conversation's run; empty when it is
     * not ours to deliver — not a subagent's run, stopped by the user, or already reported.
     *
     * <p>{@code REQUIRES_NEW}: one caller is an after-commit listener, where joining the finished
     * transaction would silently drop every write.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Prepared> prepare(UUID childRunId, boolean failed, String text) {
        AgentRun child = agentRunRepository.findById(childRunId).orElse(null);
        if (child == null) {
            return Optional.empty();
        }
        AgentSession childSession = agentSessionRepository.findById(child.getSessionId()).orElse(null);
        if (childSession == null || childSession.getParentSessionId() == null) {
            return Optional.empty();
        }
        if (child.getCancelRequestedAt() != null || child.getStatus() == RunStatus.CANCELLED) {
            log.info("subagent run {} was stopped — no report", childRunId);
            return Optional.empty();
        }
        // An answer given while a detached call or another run is still pending is interim («the
        // image is being generated»): it stays in the subagent's history, and the run that brings
        // the rest reports.
        if (agentRunRepository.isSessionBusyExcept(child.getSessionId(), childRunId,
                LocalDateTime.now().minus(RunActivityService.STALE_AFTER))) {
            log.info("subagent run {} answered while its session is still busy — interim, no report", childRunId);
            return Optional.empty();
        }
        if (agentRunRepository.claimReport(childRunId, LocalDateTime.now()) == 0) {
            log.debug("subagent run {} already reported", childRunId);
            return Optional.empty();
        }

        UUID conversationId = childSession.getParentSessionId();
        long remaining = subagentService.countWorking(conversationId);
        Trigger trigger = Trigger.fromSource(
                SubagentService.CONNECTOR_CODE,
                childSession.getConnectionId().toString(),
                SubagentService.REPORT_TRIGGER,
                childRunId.toString(),
                data(childSession, failed, text, remaining),
                Instant.now());
        TriggerLog triggerLog = triggerLogService.createTriggerLog(childSession.getUserId(), trigger);

        Channels channels = askerChannels(child).orElseGet(() -> historyOnly(conversationId));
        AgentRun run = agentRunRepository.save(AgentRun.builder()
                .triggerLog(triggerLog)
                .agent(child.getAgent())
                .destination(child.getAgent().getType().name())
                .sessionId(conversationId)
                .originRunId(childRunId)
                .channels(ChannelsCodec.toMap(channels))
                .build());
        log.info("subagent run {} reported into conversation {} ({} still working)",
                childRunId, conversationId, remaining);
        return Optional.of(new Prepared(run, trigger, channels));
    }

    private static Map<String, Object> data(AgentSession childSession, boolean failed, String text, long remaining) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subagentId", childSession.getId().toString());
        data.put("title", childSession.getTitle());
        data.put("status", failed ? "failed" : "done");
        data.put(failed ? "error" : "report", cap(text));
        data.put("remaining", remaining);
        return data;
    }

    /**
     * Where the run that asked answered — a person's message, a reminder, another report: the report
     * carries that conversation on the way a detached tool's result does, into the same chat.
     */
    private Optional<Channels> askerChannels(AgentRun child) {
        return asker(child)
                .map(asker -> ChannelsCodec.fromMap(asker.getChannels()))
                .map(Channels::continuation);
    }

    /**
     * The run outside the subagent's session that asked for this work. The reporting run is not
     * always the one the request started: a {@code tool_completed} run's origin is the subagent run
     * whose call it was, so the chain is followed until it leaves the session — stopping at the
     * direct origin would answer the conversation into the subagent's own channel and history.
     */
    private Optional<AgentRun> asker(AgentRun child) {
        UUID originId = child.getOriginRunId();
        for (int hop = 0; originId != null && hop < MAX_ORIGIN_HOPS; hop++) {
            AgentRun origin = agentRunRepository.findById(originId).orElse(null);
            if (origin == null) {
                return Optional.empty();
            }
            if (!child.getSessionId().equals(origin.getSessionId())) {
                return Optional.of(origin);
            }
            originId = origin.getOriginRunId();
        }
        return Optional.empty();
    }

    /**
     * The asking run is gone: an answer slot with the conversation's session and no channel — the run
     * reads and writes the history, and delivery finds nowhere to send its reply.
     */
    private static Channels historyOnly(UUID conversationId) {
        return new Channels(null, null, new ChannelInfo(null, conversationId, null));
    }

    private static String cap(String value) {
        if (value == null || value.length() <= REPORT_CAP) {
            return value;
        }
        int cut = REPORT_CAP;
        if (Character.isHighSurrogate(value.charAt(cut - 1))) {
            cut--;
        }
        return value.substring(0, cut) + "…[truncated: " + value.length() + " chars total]";
    }
}
