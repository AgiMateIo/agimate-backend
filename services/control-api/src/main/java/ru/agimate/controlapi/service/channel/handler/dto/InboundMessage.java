package ru.agimate.controlapi.service.channel.handler.dto;

import ru.agimate.controlapi.service.channel.handler.ChannelHandler;

import java.util.List;

/**
 * Унифицированное входящее сообщение — результат {@link ChannelHandler#handleInput}, отдаётся воркеру.
 *
 * <p>Handler приводит разнородные триггеры (текст/аудио/фото) к этому виду. В Фазе 1 заполняется
 * только {@code text}; {@code parts} зарезервирован под медиа. Адрес ответа control-api
 * восстанавливает из сессии ({@code ChannelSessionMessage.triggerInput}), поэтому здесь его нет.
 *
 * @param text  текст сообщения (для медиа — транскрипт/подпись)
 * @param parts вложения (Фаза 1: пусто)
 */
public record InboundMessage(
        String text,
        List<Part> parts
) {

    public static InboundMessage text(String text) {
        return new InboundMessage(text, List.of());
    }
}
