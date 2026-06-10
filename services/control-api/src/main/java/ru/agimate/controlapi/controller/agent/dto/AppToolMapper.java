package ru.agimate.controlapi.controller.agent.dto;

import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.JsonSchema;
import ru.agimate.controlapi.connectors.core.dto.ToolAnnotationsSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Маппит «сырые» определения тулов APP-коннектора (из {@code App.tools}) в MCP-совместимый
 * {@link ConnectorToolSpec}. У APP-тулов нет Java-сигнатуры, поэтому {@code inputSchema} строится из
 * списка строковых параметров, а {@code outputSchema} отсутствует.
 */
@UtilityClass
public class AppToolMapper {

    @SuppressWarnings("unchecked")
    public static Map<String, ConnectorToolSpec> fromAppTools(Map<String, Object> rawTools) {
        Map<String, ConnectorToolSpec> result = new LinkedHashMap<>();
        if (rawTools == null) {
            return result;
        }
        for (var entry : rawTools.entrySet()) {
            result.put(entry.getKey(), fromAppToolEntry(entry.getKey(), (Map<String, Object>) entry.getValue()));
        }
        return result;
    }

    public static ConnectorToolSpec fromAppToolEntry(String name, Map<String, Object> value) {
        String description = value.getOrDefault("description", "").toString();
        List<String> params = value.get("params") instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : List.of();

        // MCP требует inputSchema всегда (минимум пустой object).
        Map<String, JsonSchema> properties = new LinkedHashMap<>();
        for (String p : params) {
            properties.put(p, JsonSchema.scalar("string", null));
        }
        JsonSchema inputSchema = JsonSchema.object(
                properties, params.isEmpty() ? null : List.copyOf(params), null);

        return new ConnectorToolSpec(
                name,
                null,
                description.isBlank() ? null : description,
                inputSchema,
                null,
                ToolAnnotationsSpec.DEFAULT,
                null);
    }
}
