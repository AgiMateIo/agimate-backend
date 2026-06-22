package ru.agimate.controlapi.service.channel.handler.dto;

import ru.agimate.controlapi.service.channel.handler.ChannelHandler;

import java.util.List;

/**
 * Унифицированный ответ модели — контент для {@link ChannelHandler#handleOutput} (зеркало
 * {@link InboundMessage}). Только контент: адресацию и корреляцию несёт {@link OutboundDispatch}.
 *
 * <p>Handler разворачивает его в вызов нужного тула коннектора. В Фазе 1 используется только
 * {@code text}; {@code parts} зарезервирован под медиа-ответы.
 *
 * @param text  текст ответа
 * @param parts вложения (Фаза 1: пусто)
 */
public record OutboundMessage(
        String text,
        List<Part> parts
) {

    public static OutboundMessage text(String text) {
        return new OutboundMessage(text, List.of());
    }
}
