package ru.agimate.controlapi.service.channel.handler.dto;

import ru.agimate.controlapi.service.channel.handler.ChannelHandler;

import java.util.Map;

/**
 * Конфигурация канала, передаваемая в {@link ChannelHandler}.
 *
 * <p>{@code connectorCode} и {@code identity} — первого класса (отдельные колонки {@code channels}),
 * по ним handler вызывает тулы/читает триггеры нужного коннектора. {@code settings} — произвольные
 * настройки конкретного handler'а (десериализуются им самостоятельно).
 */
public record ChannelConfig(
        String connectorCode,
        String identity,
        Map<String, Object> settings
) {

    public Object setting(String key) {
        return settings == null ? null : settings.get(key);
    }
}
