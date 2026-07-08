package ru.agimate.controlapi.service.trigger;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Каналы взаимодействия агента в рамках одного запуска. {@code prompt} — входной канал
 * (откуда пришёл триггер); {@code progress} заполняется тем же каналом, когда его handler
 * доставляет промежуточный вывод ({@code ChannelHandler.deliverProgress}, webchat);
 * {@code answer} пока не заполняется — worker фолбэчится на {@code prompt}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Channels(ChannelInfo prompt, ChannelInfo progress, ChannelInfo answer) {

    public static Channels ofPrompt(ChannelInfo prompt) {
        return new Channels(prompt, null, null);
    }
}
