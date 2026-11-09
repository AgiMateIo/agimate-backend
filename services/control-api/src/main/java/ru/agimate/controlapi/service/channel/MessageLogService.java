package ru.agimate.controlapi.service.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;
import ru.agimate.controlapi.service.dto.ToolTurnRecord;
import ru.agimate.controlapi.service.trigger.ChannelInfo;
import ru.agimate.controlapi.service.trigger.Channels;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * The single record of dialogue events (SaveMessage, protocol v2): the worker is the only writer of
 * history, and delivery into the channels is a projection of that record (routed by kind and by the
 * {@code agent_runs.channels} snapshot, a port of the chains of the former worker-side
 * OutboundPublisher).
 *
 * <p>Deliberately NOT {@code @Transactional}: first {@link MessageLogPersistence} commits the
 * history, then — in ordinary code, outside a transaction — comes best-effort delivery. A delivery
 * failure (a deleted channel, a broken handler) does not roll the history back, and the transactional
 * steps inside delivery (the tool log) commit on their own and are visible to the async executor. A
 * crash between the commit and the delivery loses no message: the worker's step is retried, the
 * record is deduplicated by {@code (run_id, seq)} and the delivery by the deterministic
 * {@code message_id}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageLogService {

    private final MessageLogPersistence persistence;
    private final ChannelMessageOutboundService outboundService;

    /**
     * @param cancelled the user asked the run to stop; the worker reads it off this answer at its seam
     * @param steered   the run's inbound was absorbed by another run of the session; carried by the
     *                  seq 0 ack, on which the worker leaves quietly
     */
    public record SaveResult(boolean duplicate, boolean cancelled, boolean steered) {}

    public SaveResult save(UUID agentId, UUID triggerId, int seq, ChannelSessionMessageKind kind,
                           String progressType, String text) {
        return save(agentId, triggerId, seq, kind, progressType, text, null);
    }

    /**
     * @param toolTurn the structural turn behind a {@code TOOL_CALL}/{@code TOOL_RESULT} line; its ids
     *                 are the link between the delivered message and {@code tool_call_logs}. The
     *                 history itself still comes from the turn ledger — the turn is not stored here.
     */
    public SaveResult save(UUID agentId, UUID triggerId, int seq, ChannelSessionMessageKind kind,
                           String progressType, String text, ToolTurnRecord toolTurn) {
        MessageLogPersistence.Persisted persisted = persistence.persist(
                agentId, triggerId, seq, kind, progressType, text, toolTurn);
        deliverBestEffort(triggerId, agentId, persisted.channels(), kind, progressType, text, seq, toolTurn);
        return new SaveResult(persisted.duplicate(), persisted.cancelRequested(), persisted.steered());
    }

    private void deliverBestEffort(UUID runId, UUID agentId, Channels channels,
                                   ChannelSessionMessageKind kind, String progressType, String text, int seq,
                                   ToolTurnRecord toolTurn) {
        try {
            deliver(runId, agentId, channels, kind, progressType, text, seq, toolTurn);
        } catch (Exception e) {
            log.warn("delivery failed for run={} seq={} kind={} — history-only: {}",
                    runId, seq, kind, e.getMessage());
        }
    }

    /**
     * A delivered tool-call line names its calls by id; the channel stores the ids and the inline view
     * resolves arguments and results from {@code tool_call_logs} when it mounts. A {@code TOOL_RESULT}
     * line names none — it is history-only and never delivered.
     */
    private static List<String> toolCallIds(ChannelSessionMessageKind kind, String progressType,
                                            ToolTurnRecord toolTurn) {
        if (kind != ChannelSessionMessageKind.PROGRESS || !"TOOL_CALL".equals(progressType)
                || toolTurn == null) {
            return List.of();
        }
        return toolTurn.callIds();
    }

    /**
     * Delivery is a projection of the record. The routing chains are a port of the worker-side
     * OutboundPublisher: PROGRESS → progress; ANSWER → answer, else prompt; ERROR → progress, else
     * answer, else prompt. With no channel the event stays in the history and the run's row alone.
     */
    private void deliver(UUID runId, UUID agentId, Channels channels, ChannelSessionMessageKind kind,
                         String progressType, String text, int seq, ToolTurnRecord toolTurn) {
        if (channels == null || kind == ChannelSessionMessageKind.INBOUND
                || text == null || text.isBlank()) {
            return;
        }
        ChannelInfo prompt = channels.prompt();
        ChannelInfo progress = channels.progress();
        ChannelInfo answer = channels.answer() != null ? channels.answer() : prompt;
        ChannelInfo target = switch (kind) {
            case PROGRESS -> progress;
            case ANSWER -> answer;
            case ERROR -> progress != null ? progress : answer;
            default -> null;
        };
        if (target == null || target.channelId() == null) {
            log.info("agent output [{}] run={}: no channel, history-only", kind, runId);
            return;
        }
        String messageId = deterministicId(runId, seq);
        outboundService.send(agentId, target,
                OutboundMessage.text(text), messageId, kind.name().toLowerCase(), progressType, runId,
                toolCallIds(kind, progressType, toolTurn));
    }

    /** A deterministic message_id from (run_id, seq): a retry sends the same id and downstream deduplicates. */
    private static String deterministicId(UUID runId, int seq) {
        String name = "agimate-msglog:" + runId + ":" + seq;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
