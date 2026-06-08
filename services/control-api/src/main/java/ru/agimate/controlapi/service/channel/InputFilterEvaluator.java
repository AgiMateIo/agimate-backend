package ru.agimate.controlapi.service.channel;

import lombok.experimental.UtilityClass;

import java.util.Map;
import java.util.Objects;

@UtilityClass
public class InputFilterEvaluator {

    public static boolean matches(Map<String, Object> filter, Map<String, Object> data) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        if (data == null) {
            return false;
        }
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            Object actual = resolvePath(data, entry.getKey());
            if (!valuesMatch(entry.getValue(), actual)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public static Object resolvePath(Map<String, Object> data, String path) {
        if (data == null || path == null || path.isBlank()) {
            return null;
        }
        Object current = data;
        for (String segment : path.split("\\.")) {
            if (segment.isEmpty()) {
                return null;
            }
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static boolean valuesMatch(Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            return true;
        }
        if (expected == null || actual == null) {
            return false;
        }
        if (expected instanceof Number en && actual instanceof Number an) {
            return en.doubleValue() == an.doubleValue();
        }
        return expected.toString().equals(actual.toString());
    }
}
