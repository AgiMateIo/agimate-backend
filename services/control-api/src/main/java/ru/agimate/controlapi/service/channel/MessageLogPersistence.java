package ru.agimate.controlapi.service.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.service.AgentRunTurnService;
import ru.agimate.controlapi.service.channel.handler.ChannelMessageLog;
import ru.agimate.controlapi.service.channel.handler.ChannelMessageLogResolver;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.ChannelsCodec;
import ru.agimate.controlapi.service.session.AgentSessionService;
import ru.agimate.controlapi.service.trigger.RunFinished;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The transactional part of SaveMessage: dialogue history plus the run's status, in one transaction.
 * Delivery over the channels is done by {@link MessageLogService} after the commit.
 *
 * <p>Idempotency: UNIQUE {@code (run_id, seq)} through ON CONFLICT DO NOTHING — a retry of the DBOS
 * step produces no duplicate in history.
 *
 * <p>INBOUND is the «agent received it» ack: the worker sends no text, and the canonical form is
 * taken from the trigger's persistent data ({@link InboundTextResolver} / a compact JSON of the
 * event); {@code trigger_input} is filled from {@code trigger_log.input} of a channel message only —
 * the old reply-address lookup for snapshots with no address, which an event must not overwrite.
 *
 * <p>The generic projection is written only for channels whose handler has no own message log
 * ({@link ChannelMessageLog}): webchat stores its conversation in {@code webchat_messages} and is
 * skipped here; external channels and ACP are served by this table.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageLogPersistence {

    private final AgentRunRepository agentRunRepository;
    private final ChannelSessionMessageRepository messageRepository;
    private final InboundTextResolver inboundTextResolver;
    private final AgentRunTurnService turnService;
    private final ApplicationEventPublisher eventPublisher;
    private final AgentSessionService agentSessionService;
    private final ChannelMessageLogResolver channelMessageLogResolver;

    /**
     * @param cancelRequested rides back to the worker in the SaveMessage answer — the whole cancel transport
     * @param steered this run's inbound was absorbed by another run (both steering facts hold and
     *                the main finished DONE/CANCELLED) — the worker leaves quietly, like a queued
     *                cancellation
     */
    public record Persisted(boolean duplicate, Channels channels, boolean cancelRequested, boolean steered) {}

    @Transactional
    public Persisted persist(UUID agentId, UUID triggerId, int seq, ChannelSessionMessageKind kind,
                             String progressType, String text) {
        AgentRun run = agentRunRepository.findById(triggerId)
                .orElseThrow(() -> new NotFoundStatusException("Run not found: " + triggerId));
        if (!run.getAgent().getId().equals(agentId)) {
            throw new BadRequestStatusException("Run " + triggerId + " does not belong to agent " + agentId);
        }

        // Computed only at the ack: it costs a read of the main's row, and by the queue's contract
        // the answer cannot change later in the run — a run that starts was not stood aside.
        boolean steered = kind == ChannelSessionMessageKind.INBOUND && standsAside(run);
        RunStatus before = run.getStatus();
        projectStatus(run, kind, steered);

        // The run's outcome goes into the agent_runs row for ANY run (a self-sufficient run row: the final
        // answer and the error are visible with no join to channel_session_messages), regardless of channel
        // delivery. The run is managed (findById within this TX) → dirty checking flushes it at commit.
        if (kind == ChannelSessionMessageKind.ANSWER) {
            run.setResult(text);
            // The run is over, so its turn ledger is final — the one moment where checking it costs two
            // queries instead of repeating the work on every later assembly of history.
            run.setTurnsIntact(turnService.isLedgerIntact(triggerId));
        } else if (kind == ChannelSessionMessageKind.ERROR) {
            run.setError(text);
        }

        Channels channels = ChannelsCodec.fromMap(run.getChannels());
        // The channel's session, not the run's: every run has a session now, but this table is the
        // projection of a conversation, and a trigger run has no conversation to project into.
        UUID sessionId = Channels.sessionIdOf(channels);
        boolean duplicate = false;
        // «Working now» in the chat list: a row built mid-run says so, and only this keeps it from
        // saying so forever. A session with no channel is left out — an event per trigger run is noise.
        if (sessionId != null && run.getStatus() != before) {
            agentSessionService.runStateChanged(sessionId);
        }

        // A channel run: those same ANSWER/ERROR are additionally projected into channel_session_messages
        // (delivery), unless the channel plugin keeps its own message log. A direct run with no channel:
        // there is no history row, and the outcome is already recorded on the run above.
        if (sessionId != null) {
            // A blank PROGRESS line (TOOL_RESULT) carries nothing to show: the turn lives in the ledger,
            // and an empty projection row would only be filtered out by every reader.
            boolean blankProgress = kind == ChannelSessionMessageKind.PROGRESS
                    && (text == null || text.isBlank());
            if (!blankProgress && !ownsMessageLog(sessionId)) {
                String message = kind == ChannelSessionMessageKind.INBOUND
                        ? canonicalInbound(run, channels)
                        : text;
                String triggerInput = kind == ChannelSessionMessageKind.INBOUND && channels.prompt() != null
                        ? JsonUtils.writeValueAsString(run.getTriggerLog().getInput())
                        : null;
                int inserted = messageRepository.insertIgnoreConflict(
                        sessionId, agentId, triggerId, seq, kind.name(), progressType, message,
                        triggerInput);
                duplicate = inserted == 0;
            }
            if (kind == ChannelSessionMessageKind.ANSWER && run.getStatus() == RunStatus.DONE) {
                eventPublisher.publishEvent(new RunFinished(triggerId, agentId, run.getAgent().getUserId(), sessionId));
            }
        }

        if (duplicate) {
            log.debug("saveMessage duplicate run={} seq={} kind={}", triggerId, seq, kind);
        }
        return new Persisted(duplicate, channels, run.getCancelRequestedAt() != null, steered);
    }

    /**
     * Does this run stand aside as steered? Both facts must hold: the claim alone may be one whose
     * response the main never received (nothing was absorbed), and a confirmed absorption whose
     * main FAILED never answered the user — in either case the run must execute. Read at the seq 0
     * ack, when the main is already terminal by the queue's contract; a non-terminal main (the
     * contract somehow broken) answers «execute», trading a possible duplicate for a possible loss.
     */
    private boolean standsAside(AgentRun run) {
        if (run.getSteeredAt() == null || run.getMainRunId() == null) {
            return false;
        }
        RunStatus mainStatus = agentRunRepository.findStatusById(run.getMainRunId()).orElse(null);
        return mainStatus == RunStatus.DONE || mainStatus == RunStatus.CANCELLED;
    }

    /**
     * The run's status is a projection of the SaveMessage stream (observability; the single writer
     * keeps the order): INBOUND → RUNNING, ANSWER → DONE, ERROR → FAILED. A terminal status is never
     * rolled back (an INBOUND replay after the finish), and any event is a sign of life
     * ({@code last_activity_at} for the stuck-run sweeper).
     *
     * <p>The terminal ANSWER of a cancelled run lands in CANCELLED instead — which is also how the
     * «cancel against finish» race is settled, by which of the two got recorded first. A steered run
     * is terminal at its own ack, like one cancelled while queued — and cancellation wins when both
     * are set: the user's stop covers the absorbed message too.
     */
    private static void projectStatus(AgentRun run, ChannelSessionMessageKind kind, boolean steered) {
        RunStatus status = run.getStatus();
        boolean terminal = status == RunStatus.DONE || status == RunStatus.FAILED
                || status == RunStatus.CANCELLED || status == RunStatus.STEERED;
        if (terminal) {
            return;
        }
        boolean cancelled = run.getCancelRequestedAt() != null;
        switch (kind) {
            // A run cancelled while queued is terminal at its own ack: it leaves before doing anything,
            // so no later record would come to settle its status.
            case INBOUND -> run.setStatus(cancelled ? RunStatus.CANCELLED
                    : steered ? RunStatus.STEERED : RunStatus.RUNNING);
            case ANSWER -> run.setStatus(cancelled ? RunStatus.CANCELLED : RunStatus.DONE);
            case ERROR -> run.setStatus(RunStatus.FAILED);
            default -> { }
        }
        run.setLastActivityAt(LocalDateTime.now());
    }

    /** A channel plugin with its own message log is not projected into the generic store. */
    private boolean ownsMessageLog(UUID sessionId) {
        AgentSession session = agentSessionService.getById(sessionId);
        return channelMessageLogResolver.find(session).isPresent();
    }

    /** The canonical inbound: the channel's text (the same handleInput as at dispatch) or a compact JSON of the event. */
    private String canonicalInbound(AgentRun run, Channels channels) {
        Trigger trigger = Trigger.fromLog(run.getTriggerLog());
        if (channels != null && channels.prompt() != null) {
            return inboundTextResolver.resolveText(channels.prompt().channelId(), trigger)
                    .orElseGet(trigger::compactJson);
        }
        return trigger.compactJson();
    }

}
