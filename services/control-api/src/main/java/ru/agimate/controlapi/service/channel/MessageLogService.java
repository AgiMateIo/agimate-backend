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

    public record SaveResult(boolean duplicate) {}

    public SaveResult save(UUID agentId, UUID triggerId, int seq, ChannelSessionMessageKind kind,
                           String progressType, String text, ToolTurnRecord toolTurn) {
        MessageLogPersistence.Persisted persisted = persistence.persist(
                agentId, triggerId, seq, kind, progressType, text, toolTurn);
        deliverBestEffort(triggerId, agentId, persisted.channels(), kind, progressType, text, seq);
        return new SaveResult(persisted.duplicate());
    }

    private void deliverBestEffort(UUID runId, UUID agentId, Channels channels,
                                   ChannelSessionMessageKind kind, String progressType, String text, int seq) {
        try {
            deliver(runId, agentId, channels, kind, progressType, text, seq);
        } catch (Exception e) {
            log.warn("delivery failed for run={} seq={} kind={} — history-only: {}",
                    runId, seq, kind, e.getMessage());
        }
    }

    /**
     * Delivery is a projection of the record. The routing chains are a port of the worker-side
     * OutboundPublisher: PROGRESS → progress; ANSWER → answer, else prompt; ERROR → progress, else
     * answer, else prompt. With no channel the event stays in the history and the run's row alone.
     */
    private void deliver(UUID runId, UUID agentId, Channels channels, ChannelSessionMessageKind kind,
                         String progressType, String text, int seq) {
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
        outboundService.send(agentId, target.channelId(), target.sessionId(),
                OutboundMessage.text(text), messageId, kind.name().toLowerCase(), progressType);
    }

    /** A deterministic message_id from (run_id, seq): a retry sends the same id and downstream deduplicates. */
    private static String deterministicId(UUID runId, int seq) {
        String name = "agimate-msglog:" + runId + ":" + seq;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
