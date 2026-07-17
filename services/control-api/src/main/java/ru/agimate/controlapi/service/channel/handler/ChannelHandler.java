package ru.agimate.controlapi.service.channel.handler;

import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;
import ru.agimate.controlapi.service.channel.handler.dto.*;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Обработчик канала: мультимодальный «кодек» между коннектором и агентом.
 *
 * <p>Каждая реализация — отдельный bean с уникальным {@link #name()}, имя пишется в
 * {@code channels.channel_handler}. Поведение задаётся кодом, данные конкретного канала —
 * в {@link ChannelConfig} (connectorCode + connectionId + settings).
 *
 * <p>Тул-вызов, который handler вернул из {@link #handleOutput}, исполняется вызывающим через
 * штатную подсистему вызова тулов, поэтому ABAC-политики соблюдаются. {@link #listOfTriggers}
 * и {@link #listOfTools} нужны, чтобы при создании канала сгенерировать соответствующие
 * {@code AgentTriggerPolicy}/{@code AgentToolPolicy}.
 *
 * <p>Внутри слоя бросать только {@code ConnectorException}.
 */
public interface ChannelHandler {

    /** Уникальное в системе имя обработчика; пишется в {@code channels.channel_handler}. */
    String name();

    /**
     * JSON Schema (object) полей {@code config} — чтобы UI отрисовал форму с описаниями
     * и пользователь корректно заполнил настройки (в т.ч. параметры фильтрации).
     */
    default Map<String, Object> getConfigFields() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    /**
     * Входящие триггеры, которые обрабатывает канал с данным {@code config}.
     * Биндятся на {@link ChannelConfig#connectorCode()}/{@link ChannelConfig#connectionId()} канала
     * при генерации {@code AgentTriggerPolicy}.
     */
    List<TriggerDefinition> listOfTriggers(ChannelConfig config);

    /**
     * Тулы (connector+connectionId+name), которые handler может вызвать на исходящих —
     * для генерации {@code AgentToolPolicy}. Reply-цель может отличаться от connector/connectionId канала.
     */
    List<ToolDefinition> listOfTools(ChannelConfig config);

    /** Валидация {@code config} при создании/обновлении канала; бросает {@code ConnectorException} при ошибке. */
    void validateConfig(ChannelConfig config);

    /** Приводит триггер к унифицированному {@link InboundMessage}; {@code empty} — триггер отфильтрован/пропущен. */
    Optional<InboundMessage> handleInput(ChannelConfig config, Trigger trigger);

    /**
     * Доставлять ли промежуточный вывод агента (progress) в этот канал. {@code true} → роутер
     * заполняет progress-роль в {@code Channels} тем же каналом, и worker шлёт progress-строки
     * через {@link #handleOutput} (с {@code stream="progress"}) наравне с финальным answer.
     */
    default boolean deliverProgress(ChannelConfig config) {
        return false;
    }

    /**
     * Приносит ли канал собственные тулы своего коннектора в контекст DIALOGUE-рана — мимо
     * скилл-гейта ({@code requiredConnectors}). {@code true} → {@code RunContextService} подмешивает
     * тулы коннектора prompt-канала независимо от скиллов агента. Семантика «канал приносит тулы»:
     * IDE-коннектор отдаёт fs/terminal-тулы, пока разговор идёт из IDE, без ручной настройки скилла.
     */
    default boolean contributesPromptTools() {
        return false;
    }

    /**
     * Поддерживает ли handler вложения в исходящем ответе ({@link OutboundMessage#parts()}).
     * {@code true} → {@code RunContextService} объясняет агенту attach-конвенцию
     * ({@code [[attach:agf_…]]}), а {@link #handleOutput} обязан доставить parts.
     * {@code false} → parts молча не доставляются (маркеры из текста всё равно вырезаны).
     */
    default boolean supportsOutboundAttachments() {
        return false;
    }

    /**
     * Маппит ответ модели на действия канала. Handler либо доставляет сам (webchat/acp — пуш в
     * живое соединение) и возвращает пустой список, либо возвращает {@link ToolCallRequest}'ы —
     * их исполняет вызывающий (идемпотентность + ABAC + диспатч после коммита лога). Ключ
     * идемпотентности — {@link OutboundDispatch#messageId()} (для дополнительных запросов —
     * детерминированный суффикс); адрес ответа — из {@link OutboundDispatch#replyContext()}.
     * Побочных эффектов с тулами внутри handler'а нет — это разрывает цикл бинов с роутером
     * инбаунда и держит диспатч вне транзакций.
     */
    List<ToolCallRequest> handleOutput(ChannelConfig config, OutboundMessage outbound,
                                       OutboundDispatch dispatch);
}
