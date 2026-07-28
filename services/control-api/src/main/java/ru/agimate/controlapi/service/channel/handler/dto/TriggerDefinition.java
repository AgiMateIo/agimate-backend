package ru.agimate.controlapi.service.channel.handler.dto;

import ru.agimate.controlapi.service.channel.handler.ChannelHandler;

/**
 * Description of an incoming trigger a {@link ChannelHandler} can handle.
 *
 * <p>The connector is taken from the channel's {@link ChannelConfig#connectorCode()}, and only the
 * trigger's name is given here (e.g. {@code "message_received"}). Used to generate an
 * {@code AgentConnectionPolicy} rule of kind {@code TRIGGER} for each of the channel's triggers.
 */
public record TriggerDefinition(String triggerName) {
}
