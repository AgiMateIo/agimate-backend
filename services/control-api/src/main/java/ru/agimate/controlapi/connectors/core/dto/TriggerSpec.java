package ru.agimate.controlapi.connectors.core.dto;

import java.util.List;

/**
 * Декларация триггера, который коннектор умеет порождать.
 *
 * @param description человекочитаемое описание
 * @param params      имена параметров, доступных в {@code trigger.data}
 * @param context     директивы контекста рана ({@code null} — базовый route-пресет); см.
 *                    {@link ContextDirectives} — trust-поля валидируются на бутстрапе
 */
public record TriggerSpec(
        String description,
        List<String> params,
        ContextDirectives context
) {

    public TriggerSpec {
        params = params == null ? List.of() : List.copyOf(params);
    }

    public TriggerSpec(String description, List<String> params) {
        this(description, params, null);
    }
}
