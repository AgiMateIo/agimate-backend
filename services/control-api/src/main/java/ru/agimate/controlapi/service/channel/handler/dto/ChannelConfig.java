package ru.agimate.controlapi.service.channel.handler.dto;

import ru.agimate.controlapi.service.channel.handler.ChannelHandler;

import java.util.Map;
import java.util.UUID;

/**
 * Дескриптор канала, передаваемый в {@link ChannelHandler}.
 *
 * <p>{@code agentId}, {@code connectorCode}, {@code connectionId} — первого класса (отдельные колонки
 * {@code channels}). {@code agentId} — владелец канала; под ним {@code handleOutput} диспатчит
 * reply-тул (использовать чужой канал нельзя — проверка владения на границе сервиса). По
 * {@code connectorCode}/{@code connectionId} handler вызывает тулы/читает триггеры нужного коннектора.
 * {@code settings} — произвольные настройки конкретного handler'а (десериализуются им самостоятельно).
 */
public record ChannelConfig(
        UUID agentId,
        String connectorCode,
        String connectionId,
        Map<String, Object> settings
) {

    public Object setting(String key) {
        return settings == null ? null : settings.get(key);
    }
}
