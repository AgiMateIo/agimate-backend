package ru.agimate.common.util;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class JsonUtils {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(new JavaTimeModule())
            .registerModule(new KotlinModule.Builder().build());

    private static final ObjectMapper SNAKE_CASE_MAPPER;

    static {
        var factory = JsonFactory.builder()
                .enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .build();

        SNAKE_CASE_MAPPER = new ObjectMapper(factory)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .registerModule(new JavaTimeModule())
                .registerModule(new KotlinModule.Builder().build());
    }

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

    public static <T> T readValueSnakeCase(String value, Class<T> vClass) {
        try {
            return SNAKE_CASE_MAPPER.readValue(value, vClass);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed to convert String to object: " + ex.getMessage(), ex);
        }
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

    @Nullable
    public static String writeValueAsStringSafe(Object object) {
        try {
            return MAPPER.writeValueAsString(object);
        } catch (Exception ex) {
            return null;
        }
    }

    public static <T> Optional<T> fromJson(byte[] jsonBytes, Class<T> clazz) {
        try {
            return Optional.of(MAPPER.readValue(jsonBytes, clazz));
        } catch (IOException e) {
            log.info("JSON parse error: {}. Input: {}", e.getMessage(), jsonBytes != null ? new String(jsonBytes) : null);
        }
        return Optional.empty();
    }

    public static <T> Optional<T> fromJson(String json, Class<T> clazz) {
        try {
            return Optional.of(MAPPER.readValue(json, clazz));
        } catch (Exception e) {
            log.info("JSON parse error: {}. Input: {}", e.getMessage(), json);
        }
        return Optional.empty();
    }

    public static <T> Optional<T> fromMap(Map<String, ?> json, Class<T> clazz) {
        try {
            return Optional.of(MAPPER.convertValue(json, clazz));
        } catch (Exception e) {
            log.warn("Failed to convert JSON from map: {}. Input: {}", e.getMessage(), json);
        }
        return Optional.empty();
    }

    public static <T> Optional<T> fromJson(String json, TypeReference<T> typeReference) {
        try {
            return Optional.of(MAPPER.readValue(json, typeReference));
        } catch (Exception e) {
            log.info("JSON parse error: {}. Input: {}", e.getMessage(), json);
        }
        return Optional.empty();
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

    public static Map<String, Object> loadJsonIntoMap(File jsonFile) {
        try {
            return MAPPER.readValue(jsonFile, MAP_TYPE_REFERENCE);
        } catch (IOException e) {
            log.error("JSON parse error for file: {}", jsonFile.getPath(), e);
            throw new RuntimeException(e);
        }
    }
}
