package ru.agimate.controlapi.connectors.core.dto;

import java.util.List;

/**
 * Декларация триггера, который коннектор умеет порождать.
 *
 * @param description человекочитаемое описание
 * @param params      имена параметров, доступных в {@code trigger.data}
 */
public record TriggerSpecification(
        String description,
        List<String> params
) {

    public TriggerSpecification {
        params = params == null ? List.of() : List.copyOf(params);
    }
}
