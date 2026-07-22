package ru.agimate.controlapi.service.trigger;

import lombok.experimental.UtilityClass;
import ru.agimate.common.util.JsonUtils;

import java.util.Map;

/**
 * Типизация JSONB-снапшота {@code agent_runs.channels}: entity хранит сырую мапу
 * (database-слой не зависит от service-типов), сервисы работают с {@link Channels}.
 */
@UtilityClass
public class ChannelsCodec {

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(Channels channels) {
        return channels == null ? null : JsonUtils.MAPPER.convertValue(channels, Map.class);
    }

    public static Channels fromMap(Map<String, Object> map) {
        return map == null ? null : JsonUtils.MAPPER.convertValue(map, Channels.class);
    }
}
