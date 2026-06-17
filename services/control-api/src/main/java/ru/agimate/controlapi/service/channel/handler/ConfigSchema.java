package ru.agimate.controlapi.service.channel.handler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Мини-билдер JSON Schema (object) для {@link ChannelHandler#getConfigFields()}.
 * Порядок свойств сохраняется (для рендера формы в UI).
 */
public final class ConfigSchema {

    private ConfigSchema() {
    }

    public static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        m.put("required", List.of(required));
        return m;
    }

    public static Map<String, Object> prop(String type, String title, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        if (title != null) m.put("title", title);
        if (description != null) m.put("description", description);
        return m;
    }

    public static Map<String, Object> arrayProp(String itemType, String title, String description) {
        Map<String, Object> m = prop("array", title, description);
        m.put("items", Map.of("type", itemType));
        return m;
    }
}
