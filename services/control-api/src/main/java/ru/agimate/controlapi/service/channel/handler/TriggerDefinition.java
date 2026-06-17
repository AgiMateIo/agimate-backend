package ru.agimate.controlapi.service.channel.handler;

/**
 * Описание входящего триггера, который умеет обрабатывать {@link ChannelHandler}.
 *
 * <p>Коннектор берётся из {@link ChannelConfig#connectorCode()} канала, здесь — только имя триггера
 * (например {@code "telegram.message_received"}). Используется для генерации {@code AgentTriggerPolicy}
 * на каждый триггер канала.
 */
public record TriggerDefinition(String triggerName) {
}
