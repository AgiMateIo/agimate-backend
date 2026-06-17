package ru.agimate.controlapi.service.channel.handler;

import java.util.List;
import java.util.Map;

/**
 * Унифицированный ответ модели — вход {@link ChannelHandler#process}.
 *
 * <p>Handler разворачивает его в вызов нужного тула коннектора. В Фазе 1 используется только
 * {@code text}; {@code parts} зарезервирован под медиа-ответы.
 *
 * @param text         текст ответа
 * @param parts        вложения (Фаза 1: пусто)
 * @param replyContext корреляция входящего сообщения, проброшенная через сессию (адресат ответа)
 */
public record OutboundMessage(
        String text,
        List<Part> parts,
        Map<String, Object> replyContext
) {

    public static OutboundMessage text(String text, Map<String, Object> replyContext) {
        return new OutboundMessage(text, List.of(), replyContext);
    }
}
