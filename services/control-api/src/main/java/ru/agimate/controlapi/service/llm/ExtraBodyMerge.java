package ru.agimate.controlapi.service.llm;

import lombok.experimental.UtilityClass;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deep-merge двух extra_body: пер-модельный оверрайд поверх провайдер-уровневого.
 * Семантика: вложенные объекты мёржатся рекурсивно, на конфликте скаляров побеждает
 * оверрайд, массивы заменяются целиком (не конкатенируются — предсказуемее для
 * списков вроде OpenRouter {@code provider.only}).
 */
@UtilityClass
public class ExtraBodyMerge {

    /** null-безопасно: null-аргумент = пустой уровень; оба null → пустая мапа. */
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
