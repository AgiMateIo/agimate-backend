package ru.agimate.controlapi.service.llm;

import lombok.experimental.UtilityClass;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deep merge of two extra_body maps: the per-model override on top of the provider-level one. The
 * semantics: nested objects merge recursively, on a scalar conflict the override wins, and arrays are
 * replaced wholesale (not concatenated — more predictable for lists such as OpenRouter's
 * {@code provider.only}).
 */
@UtilityClass
public class ExtraBodyMerge {

    /** Null-safe: a null argument means an empty level; both null → an empty map. */
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (base != null) {
            result.putAll(base);
        }
        if (override == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            Object existing = result.get(entry.getKey());
            if (existing instanceof Map<?, ?> && entry.getValue() instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> existingMap = (Map<String, Object>) existing;
                @SuppressWarnings("unchecked")
                Map<String, Object> overrideMap = (Map<String, Object>) entry.getValue();
                result.put(entry.getKey(), merge(existingMap, overrideMap));
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }
}
