package ru.agimate.controlapi.service.webchat;

import java.util.UUID;

/**
 * Payload события {@code webchat_message} в Centrifugo-канале {@code webchat:{sessionId}}.
 * События доставляются at-least-once — фронт дедуплицирует по {@code messageId}.
 *
 * @param direction {@code USER} (echo сообщения пользователя) или {@code AGENT}
 * @param stream    поток вывода агента: {@code answer}/{@code progress}/{@code error}; null для USER
 */
public record WebchatMessageEvent(
        UUID sessionId,
        UUID channelId,
        UUID agentId,
        String messageId,
        String direction,
        String stream,
        String text,
        String createdAt
) {
}
