package ru.agimate.controlapi.service.channel.handler.dto;

import ru.agimate.controlapi.service.channel.handler.ChannelHandler;

import java.util.List;
import java.util.Map;

/**
 * Унифицированный ответ модели — вход {@link ChannelHandler#handleOutput}.
 *
 * <p>Handler разворачивает его в вызов нужного тула коннектора. В Фазе 1 используется только
 * {@code text}; {@code parts} зарезервирован под медиа-ответы.
 *
 * @param text         текст ответа
 * @param parts        вложения (Фаза 1: пусто)
 * @param replyContext корреляция входящего сообщения, проброшенная через сессию (адресат ответа)
 * @param messageId    id исходящего сообщения от агента — ключ идемпотентности дисптача ответа
 */
public record OutboundMessage(
        String text,
        List<Part> parts,
        Map<String, Object> replyContext,
        String messageId
) {

    public static OutboundMessage text(String text, Map<String, Object> replyContext, String messageId) {
        return new OutboundMessage(text, List.of(), replyContext, messageId);
    }
}
