package ru.agimate.controlapi.service.channel.handler;

import java.util.List;
import java.util.Map;

/**
 * Унифицированное входящее сообщение — результат {@link ChannelHandler#convert}.
 *
 * <p>Handler приводит разнородные триггеры (текст/аудио/фото) к этому виду. В Фазе 1 заполняется
 * только {@code text}; {@code parts} зарезервирован под медиа.
 *
 * @param text            текст сообщения (для медиа — транскрипт/подпись)
 * @param parts           вложения (Фаза 1: пусто)
 * @param replyContext    корреляция для ответа (например chat_id), сохраняется в сессии и
 *                        используется {@link ChannelHandler#process} для восстановления адресата
 * @param conversationKey ключ разговора для ключевания сессии (null → сессия на канал)
 */
public record InboundMessage(
        String text,
        List<Part> parts,
        Map<String, Object> replyContext,
        String conversationKey
) {

    public static InboundMessage text(String text, Map<String, Object> replyContext, String conversationKey) {
        return new InboundMessage(text, List.of(), replyContext, conversationKey);
    }
}
