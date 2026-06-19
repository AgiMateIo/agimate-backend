package ru.agimate.controlapi.service.trigger;

import java.util.UUID;

/**
 * Ссылка на канал в конкретной роли (prompt/progress/answer).
 *
 * @param channelId канал, через который идёт взаимодействие
 * @param sessionId активная сессия канала; для prompt её id пишется в {@code TriggerLogAgent.sessionId}
 * @param messageId id входящего/целевого сообщения в канале (треды/ответы); пока не заполняется
 */
public record ChannelInfo(UUID channelId, UUID sessionId, String messageId) {
}
