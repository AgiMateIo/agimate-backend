package ru.agimate.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The project's Jackson façade. This is Jackson 2 on purpose: it is the generation Hibernate uses for
 * JSONB columns, while Spring's injectable mapper is Jackson 3 — see the boundary in CLAUDE.md.
 *
 * <p>Two failure contracts, and which one applies is visible at the call site: a method either throws,
 * or its return type carries the failure ({@code Optional}, an empty map). The {@code …OrNull} suffix
 * marks the one method that answers with a bare {@code null}.
 *
 * <p><b>No method here reports the document it failed on.</b> What flows through is credentials,
 * chat messages and tool arguments, and a parse failure is exactly when the raw text would be logged.
 * Jackson's own message is safe to pass on only because {@code StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION}
 * is off by default, which makes the location it appends read {@code [Source: REDACTED]} — do not
 * enable that feature on {@link #MAPPER}.
 */
@Slf4j
public class JsonUtils {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(new JavaTimeModule());

    public static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {
    };

    public static final TypeReference<Map<String, String>> MAP_STRING_TYPE_REFERENCE = new TypeReference<>() {
    };

    private JsonUtils() {
    }

    /** @throws RuntimeException the value is not JSON of the expected shape */
    public static <T> T readValue(String value, Class<T> vClass) {
        try {
            return MAPPER.readValue(value, vClass);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed to read JSON as " + vClass.getName() + ": " + ex.getMessage(), ex);
        }
    }

    /** @throws RuntimeException the value is not JSON of the expected shape */
    public static <T> T readValue(String json, TypeReference<T> typeReference) {
        try {
            return MAPPER.readValue(json, typeReference);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed to read JSON as " + typeReference.getType() + ": " + ex.getMessage(), ex);
        }
    }

    /** @return empty when the object cannot be serialized */
    public static Optional<String> toJson(Object object) {
        try {
            return Optional.ofNullable(MAPPER.writeValueAsString(object));
        } catch (JsonProcessingException ex) {
            log.error("Failed to write {} as JSON: {}", className(object), ex.getMessage(), ex);
        }
        return Optional.empty();
    }

    /** @throws RuntimeException the object cannot be serialized */
    public static String writeValueAsString(Object object) {
        try {
            return MAPPER.writeValueAsString(object);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed to write " + className(object) + " as JSON: " + ex.getMessage(), ex);
        }
    }

    /** @return an empty map for blank input as well as for unparseable input — the two are not distinguished */
    public static Map<String, Object> fromJsonToMap(String json) {
        if (StringUtils.isEmpty(json)) {
            return new HashMap<>();
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE_REFERENCE);
        } catch (Exception e) {
            log.warn("Failed to read JSON as a map: {}", e.getMessage());
        }
        return Map.of();
    }

    /** @return {@code null} when the input is not valid JSON */
    @Nullable
    public static JsonNode toJsonNodeOrNull(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            log.debug("Failed to read JSON as a tree: {}", e.getMessage());
            return null;
        }
    }

    public static Map<String, Object> objectToMap(Object obj) {
        return MAPPER.convertValue(obj, MAP_TYPE_REFERENCE);
    }

    private static final Comparator<JsonNode> NUMERIC_AWARE_LEAF_COMPARATOR = (a, b) -> {
        if (a.equals(b)) {
            return 0;
        }
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue());
        }
        return 1;
    };

    /**
     * Deep equality for arbitrary JSON-like values that tolerates numeric type drift
     * after a JSONB round-trip (e.g. Integer vs Long vs BigDecimal of the same value).
     * Object key order does not matter; array order does.
     */
    public static boolean jsonEquals(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        JsonNode na = MAPPER.valueToTree(a);
        JsonNode nb = MAPPER.valueToTree(b);
        return na.equals(NUMERIC_AWARE_LEAF_COMPARATOR, nb);
    }

    /** The type, never the value — a failed serialization must not put the object into the message. */
    private static String className(Object object) {
        return object == null ? "null" : object.getClass().getName();
    }
}
