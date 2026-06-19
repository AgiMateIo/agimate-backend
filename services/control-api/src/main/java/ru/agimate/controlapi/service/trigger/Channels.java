package ru.agimate.controlapi.service.trigger;

/**
 * Каналы взаимодействия агента в рамках одного запуска. {@code prompt} — входной канал
 * (откуда пришёл триггер); {@code progress}/{@code answer} зарезервированы под стриминг
 * прогресса и финальный ответ (пока не заполняются, агент научится с ними работать позже).
 */
public record Channels(ChannelInfo prompt, ChannelInfo progress, ChannelInfo answer) {

    public static Channels ofPrompt(ChannelInfo prompt) {
        return new Channels(prompt, null, null);
    }
}
