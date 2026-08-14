package ru.agimate.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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

    public static <T> T readValue(String value, Class<T> vClass) {
        try {
            return MAPPER.readValue(value, vClass);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed to convert String [" + value + "] to object: [" + vClass.getName() + "] because " + ex.getMessage(), ex);
        }
    }

    public static <T> T readValue(String json, TypeReference<T> typeReference) {
        try {
            return MAPPER.readValue(json, typeReference);
        } catch (Exception e) {
            log.info("JSON parse error: {}. Input: {}", e.getMessage(), json);
        }
        return null;
    }

    public static Optional<String> toJson(Object object) {
        try {
            return Optional.ofNullable(MAPPER.writeValueAsString(object));
        } catch (JsonProcessingException ex) {
            log.error("Failed to convert object to String: {}", ex.getMessage(), ex);
        }
        return Optional.empty();
    }

    public static String writeValueAsString(Object object) {
        try {
            return MAPPER.writeValueAsString(object);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed to convert object to String: " + ex.getMessage(), ex);
        }
    }

    public static Map<String, Object> fromJsonToMap(String json) {
        if (StringUtils.isEmpty(json)) {
            return new HashMap<>();
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE_REFERENCE);
        } catch (Exception e) {
            log.warn("JSON parse error: {}", json, e);
        }
        return Map.of();
    }

    public static JsonNode toJsonNode(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
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
}
