package ru.agimate.controlapi.service.trigger;

import java.util.UUID;

/**
 * @param triggerMessageField dot-path, по которому воркер извлекает текст входящего (handler 'generic')
 * @param inboundText         уже извлечённый control-api текст (handler выполнил convert() сам, например
 *                            telegram); если задан — воркер использует его вместо triggerMessageField
 */
public record ChannelContext(
        UUID channelId,
        UUID channelSessionId,
        String channelName,
        String triggerMessageField,
        String inboundText
) {
}
