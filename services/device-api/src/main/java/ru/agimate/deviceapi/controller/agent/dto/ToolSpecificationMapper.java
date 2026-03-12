package ru.agimate.deviceapi.controller.agent.dto;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;
import lombok.experimental.UtilityClass;
import ru.agimate.deviceapi.controller.agent.dto.ToolSpecificationResponse.JsonSchemaResponse;

import java.util.LinkedHashMap;
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
