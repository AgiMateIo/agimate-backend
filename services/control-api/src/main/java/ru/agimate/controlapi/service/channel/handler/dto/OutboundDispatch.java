package ru.agimate.controlapi.service.channel.handler.dto;

import ru.agimate.controlapi.service.channel.handler.ChannelHandler;

import java.util.Map;
import java.util.UUID;

/**
 * Метаданные диспатча ответа в канал — спутник {@link OutboundMessage} в
 * {@link ChannelHandler#handleOutput}. Не контент: control-api заполняет это сам.
 *
 * @param messageId    эффективный id исходящего сообщения — ключ идемпотентности reply-тула
 * @param stream       поток вывода агента: {@code answer}/{@code progress}/{@code error};
 *                     null = answer (сообщение от воркера без роли)
 * @param progressType вид progress-события ({@code THINKING}/{@code TOOL_CALL}/{@code TEXT});
 *                     null для не-progress потоков
 * @param channelId    канал, в который идёт доставка
 * @param sessionId    сессия канала, разрешённая на границе сервиса
 * @param replyContext корреляция входящего, восстановленная из сессии (адресат ответа)
 */
public record OutboundDispatch(
        String messageId,
        String stream,
        String progressType,
        UUID channelId,
        UUID sessionId,
        Map<String, Object> replyContext
) {
}
