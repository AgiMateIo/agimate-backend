package ru.agimate.controlapi.controller.agent.dto;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;
import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.controller.agent.dto.ToolSpecificationResponse.JsonSchemaResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@UtilityClass
public class ToolSpecificationMapper {

    public static ToolSpecificationResponse toResponse(ToolSpecification spec) {
        return new ToolSpecificationResponse(
                spec.name(),
                spec.description(),
                mapSchema(spec.parameters())
        );
    }

    @SuppressWarnings("unchecked")
    public static Map<String, ToolSpecificationResponse> fromAppTools(Map<String, Object> rawTools) {
        Map<String, ToolSpecificationResponse> result = new LinkedHashMap<>();
        if (rawTools == null) return result;
        for (var entry : rawTools.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> value = (Map<String, Object>) entry.getValue();
            result.put(name, fromAppToolEntry(name, value));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static ToolSpecificationResponse fromAppToolEntry(String name, Map<String, Object> value) {
        String description = value.getOrDefault("description", "").toString();
        List<String> params = value.get("params") instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : List.of();
        LinkedHashMap<String, JsonSchemaResponse> properties = new LinkedHashMap<>();
        for (String p : params) {
            properties.put(p, new JsonSchemaResponse("string", null, null, null, null, null));
        }
        JsonSchemaResponse parameters = new JsonSchemaResponse(
                "object", null, properties, List.copyOf(params), null, null);
        return new ToolSpecificationResponse(name, description, parameters);
    }

    private static JsonSchemaResponse mapSchema(JsonSchemaElement element) {
        if (element == null) return null;

        return switch (element) {
            case JsonObjectSchema obj -> new JsonSchemaResponse(
                    "object", obj.description(),
                    mapProperties(obj.properties()), obj.required(),
                    null, null);
            case JsonArraySchema arr -> new JsonSchemaResponse(
                    "array", arr.description(),
                    null, null,
                    mapSchema(arr.items()), null);
            case JsonEnumSchema en -> new JsonSchemaResponse(
                    "string", en.description(),
                    null, null,
                    null, en.enumValues());
            case JsonStringSchema s -> new JsonSchemaResponse(
                    "string", s.description(),
                    null, null, null, null);
            case JsonIntegerSchema s -> new JsonSchemaResponse(
                    "integer", s.description(),
                    null, null, null, null);
            case JsonNumberSchema s -> new JsonSchemaResponse(
                    "number", s.description(),
                    null, null, null, null);
            case JsonBooleanSchema s -> new JsonSchemaResponse(
                    "boolean", s.description(),
                    null, null, null, null);
            default -> new JsonSchemaResponse(
                    "object", element.description(),
                    null, null, null, null);
        };
    }

    private static Map<String, JsonSchemaResponse> mapProperties(Map<String, JsonSchemaElement> properties) {
        if (properties == null) return null;
        Map<String, JsonSchemaResponse> result = new LinkedHashMap<>();
        for (var entry : properties.entrySet()) {
            result.put(entry.getKey(), mapSchema(entry.getValue()));
        }
        return result;
    }
}
