package ru.agimate.controlapi.service.trigger;

import lombok.experimental.UtilityClass;
import ru.agimate.common.util.JsonUtils;

import java.util.Map;

/**
 * Typing of the {@code agent_runs.channels} JSONB snapshot: the entity stores a raw map (the database
 * layer does not depend on service types), while the services work with {@link Channels}.
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
