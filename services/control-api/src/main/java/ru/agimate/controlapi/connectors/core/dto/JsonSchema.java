package ru.agimate.controlapi.connectors.core.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Узел JSON Schema (draft-2020-12, как в MCP). Стандартные ключевые слова: {@code type},
 * {@code description}, {@code properties}, {@code required}, {@code items}, {@code enum},
 * {@code additionalProperties}. Все поля nullable — пустая схема сериализуется в {@code {}} («any»).
 *
 * <p>{@link #extra} ({@code @JsonAnyGetter}/{@code @JsonAnySetter}) собирает все прочие ключевые слова
 * ({@code anyOf}/{@code oneOf}/{@code $ref}/{@code format}/{@code default}/{@code minimum}/…). Это даёт
 * лосслесс round-trip произвольной JSON Schema с внешних MCP-серверов: неизвестные поля не теряются
 * при десериализации в этот record и обратной сериализации.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonSchema(
        String type,
        String description,
        Map<String, JsonSchema> properties,
        List<String> required,
        JsonSchema items,
        @JsonProperty("enum") List<String> enumValues,
        JsonSchema additionalProperties,
        @JsonAnyGetter @JsonAnySetter Map<String, Object> extra
) {

    /** Скаляр: string / integer / number / boolean. */
    public static JsonSchema scalar(String type, String description) {
        return new JsonSchema(type, description, null, null, null, null, null, null);
    }

    /** «Любой» тип — пустая схема {@code {}} (или только description). */
    public static JsonSchema any(String description) {
        return new JsonSchema(null, description, null, null, null, null, null, null);
    }

    /** string + {@code enum}. */
    public static JsonSchema enumString(String description, List<String> values) {
        return new JsonSchema("string", description, null, null, null, values, null, null);
    }

    public static JsonSchema array(String description, JsonSchema items) {
        return new JsonSchema("array", description, null, null, items, null, null, null);
    }

    public static JsonSchema object(Map<String, JsonSchema> properties, List<String> required, String description) {
        return new JsonSchema("object", description, properties, required, null, null, null, null);
    }

    /** {@code Map<String, V>}: object + {@code additionalProperties} = схема значения. */
    public static JsonSchema map(String description, JsonSchema valueSchema) {
        return new JsonSchema("object", description, null, null, null, null, valueSchema, null);
    }
}
