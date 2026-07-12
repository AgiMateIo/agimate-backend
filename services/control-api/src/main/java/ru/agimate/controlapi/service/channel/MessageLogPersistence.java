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
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.database.repositories.TriggerLogAgentRepository;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.ChannelsCodec;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Транзакционная часть SaveMessage: история диалога + статус рана, одной транзакцией.
 * Доставку по каналам делает {@link MessageLogService} уже после коммита.
 *
 * <p>Идемпотентность: UNIQUE {@code (run_id, seq)} через ON CONFLICT DO NOTHING — ретрай
 * DBOS-шага не даёт дубля в истории.
 *
 * <p>INBOUND — ack «агент получил»: текст воркер не шлёт, каноника берётся из персистентных
 * данных триггера ({@link InboundTextResolver} / компактный JSON события); {@code trigger_input}
 * заполняется из {@code trigger_log.input} (reply-context механика). Финальный ANSWER помечает
 * все сообщения рана {@code completed=true} — только они видны истории следующих ранов.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageLogPersistence {

    private final TriggerLogAgentRepository triggerLogAgentRepository;
    private final ChannelSessionMessageRepository messageRepository;
    private final InboundTextResolver inboundTextResolver;

    public record Persisted(boolean duplicate, Channels channels) {}

    @Transactional
    public Persisted persist(UUID agentId, UUID triggerId, int seq, ChannelSessionMessageKind kind,
                             String progressType, String text) {
        TriggerLogAgent run = triggerLogAgentRepository.findById(triggerId)
                .orElseThrow(() -> new NotFoundStatusException("Run not found: " + triggerId));
        if (!run.getAgent().getId().equals(agentId)) {
            throw new BadRequestStatusException("Run " + triggerId + " does not belong to agent " + agentId);
        }

        projectStatus(run, kind);

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

        if (duplicate) {
            log.debug("saveMessage duplicate run={} seq={} kind={}", triggerId, seq, kind);
        }
        return new Persisted(duplicate, channels);
    }

    /**
     * Статус рана — проекция потока SaveMessage (наблюдаемость; single-writer держит очередь):
     * INBOUND → RUNNING, ANSWER → DONE, ERROR → FAILED. Терминальный статус назад не
     * откатывается (реплей INBOUND после финиша), любое событие — признак жизни
     * ({@code last_activity_at} для сборщика залипших).
     */
    private static void projectStatus(TriggerLogAgent run, ChannelSessionMessageKind kind) {
        RunStatus status = run.getStatus();
        boolean terminal = status == RunStatus.DONE || status == RunStatus.FAILED
                || status == RunStatus.CANCELLED;
        if (terminal) {
            return;
        }
        switch (kind) {
            case INBOUND -> run.setStatus(RunStatus.RUNNING);
            case ANSWER -> run.setStatus(RunStatus.DONE);
            case ERROR -> run.setStatus(RunStatus.FAILED);
            default -> { }
        }
        run.setLastActivityAt(LocalDateTime.now());
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
}
