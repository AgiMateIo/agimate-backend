package ru.agimate.controlapi.service.channel.handler;

import ru.agimate.controlapi.service.tool.AgentToolCallService;
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
 * в {@link ChannelConfig} (connectorCode + identity + settings).
 *
 * <p>Вызовы тулов внутри {@link #handleOutput} идут через штатную подсистему вызова тулов,
 * поэтому ABAC-политики соблюдаются. {@link #listOfTriggers} и {@link #listOfTools} нужны,
 * чтобы при создании канала сгенерировать соответствующие {@code AgentTriggerPolicy}/{@code AgentToolPolicy}.
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
     * Биндятся на {@link ChannelConfig#connectorCode()}/{@link ChannelConfig#identity()} канала
     * при генерации {@code AgentTriggerPolicy}.
     */
    List<TriggerDefinition> listOfTriggers(ChannelConfig config);

    /**
     * Тулы (connector+identity+name), которые handler может вызвать на исходящих —
     * для генерации {@code AgentToolPolicy}. Reply-цель может отличаться от connector/identity канала.
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
     * Отправляет ответ модели в канал: выбирает тул и аргументы и вызывает его через
     * {@link AgentToolCallService#processToolCall} (идемпотентность + проверка ABAC). Ключ
     * идемпотентности — {@link OutboundDispatch#messageId()}; адрес ответа — из
     * {@link OutboundDispatch#replyContext()}.
     *
     * <p>{@code toolCallService} передаётся параметром, а не инжектится в handler — иначе бин
     * handler'а тянул бы {@code ConnectorService} и замыкал цикл с роутером инбаунда.
     */
    void handleOutput(ChannelConfig config, OutboundMessage outbound, OutboundDispatch dispatch,
                      AgentToolCallService toolCallService);
}
