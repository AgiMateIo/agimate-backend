package ru.agimate.controlapi.connectors.core.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * A JSON Schema node (draft-2020-12, as used by MCP). Standard keywords: {@code type},
 * {@code description}, {@code properties}, {@code required}, {@code items}, {@code enum},
 * {@code additionalProperties}. Every field is nullable — an empty schema serialises to {@code {}} («any»).
 *
 * <p>{@link #extra} ({@code @JsonAnyGetter}/{@code @JsonAnySetter}) collects every other keyword
 * ({@code anyOf}/{@code oneOf}/{@code $ref}/{@code format}/{@code default}/{@code minimum}/…). That gives a
 * lossless round-trip of an arbitrary JSON Schema coming from an external MCP server: unknown fields
 * survive deserialisation into this record and serialisation back out.
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

/** A scalar: string / integer / number / boolean. */
    public static JsonSchema scalar(String type, String description) {
        return new JsonSchema(type, description, null, null, null, null, null, null);
    }

/** The «any» type — an empty schema {@code {}} (or description only). */
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

/** {@code Map<String, V>}: object + {@code additionalProperties} = the value's schema. */
    public static JsonSchema map(String description, JsonSchema valueSchema) {
        return new JsonSchema("object", description, null, null, null, null, valueSchema, null);
    }
}
