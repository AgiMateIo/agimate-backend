package ru.agimate.controlapi.service.channel.handler.dto;

import ru.agimate.controlapi.service.channel.handler.ChannelHandler;

import java.util.Map;

/**
 * Метаданные диспатча ответа в канал — спутник {@link OutboundMessage} в
 * {@link ChannelHandler#handleOutput}. Не контент: control-api заполняет это сам.
 *
 * @param messageId    эффективный id исходящего сообщения — ключ идемпотентности reply-тула
 * @param replyContext корреляция входящего, восстановленная из сессии (адресат ответа)
 */
public record OutboundDispatch(
        String messageId,
        Map<String, Object> replyContext
) {
}
