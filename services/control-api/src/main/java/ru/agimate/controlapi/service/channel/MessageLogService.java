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
 * Единая запись событий диалога (SaveMessage, протокол v2): воркер — единственный писатель
 * истории, доставка в каналы — проекция записи (роутинг по kind и снапшоту
 * {@code trigger_log_agents.channels}, порт цепочек бывшего worker-side OutboundPublisher).
 *
 * <p>Намеренно НЕ {@code @Transactional}: сначала {@link MessageLogPersistence} коммитит историю,
 * затем — обычным кодом, вне транзакции — идёт best-effort доставка. Сбой доставки (удалённый
 * канал, сломанный handler) не откатывает историю, а транзакционные шаги внутри доставки
 * (лог тула) коммитятся сами и видны async-исполнителю. Крэш между коммитом и доставкой
 * сообщение не теряет: шаг воркера ретраится, запись дедупится по {@code (run_id, seq)},
 * доставка — по детерминированному {@code message_id}.
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
     * Доставка = проекция записи. Цепочки роутинга — порт worker-side OutboundPublisher:
     * PROGRESS → progress; ANSWER → answer, иначе prompt; ERROR → progress, иначе answer,
     * иначе prompt. Нет канала — событие остаётся только в истории/строке рана.
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

    /** Детерминированный message_id от (run_id, seq): ретрай шлёт тот же id, downstream дедупит. */
    private static String deterministicId(UUID runId, int seq) {
        String name = "agimate-msglog:" + runId + ":" + seq;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
