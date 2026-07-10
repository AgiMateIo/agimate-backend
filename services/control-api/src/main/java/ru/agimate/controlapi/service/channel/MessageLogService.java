package ru.agimate.controlapi.service.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.entities.TriggerLog;
import ru.agimate.controlapi.database.entities.TriggerLogAgent;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.database.repositories.TriggerLogAgentRepository;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;
import ru.agimate.controlapi.service.trigger.ChannelInfo;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.ChannelsCodec;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Единая запись событий диалога (SaveMessage, протокол v2): воркер — единственный писатель
 * истории, доставка в каналы — проекция записи (роутинг по kind и снапшоту
 * {@code trigger_log_agents.channels}, порт цепочек бывшего worker-side OutboundPublisher).
 *
 * <p>Идемпотентность: UNIQUE {@code (run_id, seq)} через ON CONFLICT DO NOTHING — ретрай
 * DBOS-шага не даёт дубля в истории; доставка выполняется и на ретрае (крэш между записью и
 * доставкой не теряет сообщение), дедуп downstream по детерминированному {@code message_id}.
 *
 * <p>INBOUND — ack «агент получил»: текст воркер не шлёт, каноника берётся из персистентных
 * данных триггера ({@link InboundTextResolver} / компактный JSON события); {@code trigger_input}
 * заполняется из {@code trigger_log.input} (reply-context механика). Финальный ANSWER помечает
 * все сообщения рана {@code completed=true} — только они видны истории следующих ранов.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageLogService {

    private final TriggerLogAgentRepository triggerLogAgentRepository;
    private final ChannelSessionMessageRepository messageRepository;
    private final ChannelMessageOutboundService outboundService;
    private final InboundTextResolver inboundTextResolver;

    public record SaveResult(boolean duplicate) {}

    @Transactional
    public SaveResult save(UUID agentId, UUID triggerId, int seq, ChannelSessionMessageKind kind,
                           String progressType, String text) {
        TriggerLogAgent run = triggerLogAgentRepository.findById(triggerId)
                .orElseThrow(() -> new NotFoundStatusException("Run not found: " + triggerId));
        if (!run.getAgent().getId().equals(agentId)) {
            throw new BadRequestStatusException("Run " + triggerId + " does not belong to agent " + agentId);
        }

        Channels channels = ChannelsCodec.fromMap(run.getChannels());
        UUID sessionId = run.getSessionId();
        boolean duplicate = false;

        if (sessionId != null) {
            String message = kind == ChannelSessionMessageKind.INBOUND
                    ? canonicalInbound(run, channels)
                    : text;
            String triggerInput = kind == ChannelSessionMessageKind.INBOUND
                    ? JsonUtils.writeValueAsString(run.getTriggerLog().getInput())
                    : null;
            int inserted = messageRepository.insertIgnoreConflict(
                    sessionId, agentId, triggerId, seq, kind.name(), progressType, message, triggerInput);
            duplicate = inserted == 0;
            if (kind == ChannelSessionMessageKind.ANSWER) {
                messageRepository.markRunCompleted(triggerId);
            }
        } else if (kind == ChannelSessionMessageKind.ANSWER || kind == ChannelSessionMessageKind.ERROR) {
            // Direct-ран без каналов: результат/ошибка — в строку рана, доставки нет.
            if (kind == ChannelSessionMessageKind.ANSWER) {
                run.setResult(text);
            } else {
                run.setError(text);
            }
            triggerLogAgentRepository.save(run);
        }

        deliver(run, channels, kind, text, seq);
        if (duplicate) {
            log.debug("saveMessage duplicate run={} seq={} kind={}", triggerId, seq, kind);
        }
        return new SaveResult(duplicate);
    }

    /** Каноника inbound: текст канала (тот же handleInput, что при dispatch) или компактный JSON события. */
    private String canonicalInbound(TriggerLogAgent run, Channels channels) {
        Trigger trigger = reconstructTrigger(run.getTriggerLog());
        if (channels != null && channels.prompt() != null) {
            return inboundTextResolver.resolve(channels.prompt().channelId(), trigger)
                    .orElseGet(() -> compactEvent(trigger));
        }
        return compactEvent(trigger);
    }

    private static String compactEvent(Trigger trigger) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("connectorCode", trigger.connectorCode());
        event.put("name", trigger.name());
        event.put("data", trigger.data());
        return JsonUtils.writeValueAsString(event);
    }

    private static Trigger reconstructTrigger(TriggerLog log) {
        return new Trigger(
                log.getConnectorCode(),
                log.getConnectionId(),
                log.getName(),
                log.getExternalId(),
                log.getInput(),
                log.getOccurredAt() == null ? null : log.getOccurredAt().toString(),
                null);
    }

    /**
     * Доставка = проекция записи. Цепочки роутинга — порт worker-side OutboundPublisher:
     * PROGRESS → progress; ANSWER → answer, иначе prompt; ERROR → progress, иначе answer,
     * иначе prompt. Нет канала — событие остаётся только в истории/строке рана.
     */
    private void deliver(TriggerLogAgent run, Channels channels, ChannelSessionMessageKind kind,
                         String text, int seq) {
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
            log.info("agent output [{}] run={}: no channel, history-only", kind, run.getId());
            return;
        }
        String messageId = deterministicId(run.getId(), seq);
        outboundService.send(run.getAgent().getId(), target.channelId(), target.sessionId(),
                OutboundMessage.text(text), messageId, kind.name().toLowerCase());
    }

    /** Детерминированный message_id от (run_id, seq): ретрай шлёт тот же id, downstream дедупит. */
    private static String deterministicId(UUID runId, int seq) {
        String name = "agimate-msglog:" + runId + ":" + seq;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
