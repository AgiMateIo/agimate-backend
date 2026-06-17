package ru.agimate.controlapi.service.channel.handler;

import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.List;
import java.util.Optional;

/**
 * Обработчик канала: мультимодальный «кодек» между коннектором и агентом.
 *
 * <p>Каждая реализация — отдельный bean с уникальным {@link #name()}, имя пишется в
 * {@code channels.channel_handler}. Поведение задаётся кодом, данные конкретного канала —
 * в {@link ChannelConfig} (connectorCode + identity + settings).
 *
 * <p>Вызовы тулов внутри {@link #process} идут через штатную подсистему вызова тулов,
 * поэтому ABAC-политики соблюдаются. {@link #listOfTriggers} и {@link #listOfTools} нужны,
 * чтобы при создании канала сгенерировать соответствующие {@code AgentTriggerPolicy}/{@code AgentToolPolicy}.
 *
 * <p>Внутри слоя бросать только {@code ConnectorException}.
 */
public interface ChannelHandler {

    /** Уникальное в системе имя обработчика; пишется в {@code channels.channel_handler}. */
    String name();

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
    Optional<InboundMessage> convert(ChannelConfig config, Trigger trigger);

    /**
     * Отправляет ответ модели в канал: выбирает тул и аргументы и вызывает его через
     * {@code ChannelOutboundDispatcher} (поэтому ABAC соблюдается). {@code ctx} несёт
     * уже разрешённый {@link ChannelOutboundContext#toolCallId()} (идемпотентность).
     */
    void process(ChannelConfig config, OutboundMessage outbound, ChannelOutboundContext ctx);
}
