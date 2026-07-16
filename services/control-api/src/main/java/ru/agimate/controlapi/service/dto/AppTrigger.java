package ru.agimate.controlapi.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Триггер устройства для manage-UI. {@code paramsSchema} — сырой JSON Schema полезной нагрузки
 * события ({@code null}, если устройство её не прислало); {@code params} — производный список имён
 * для простого отображения.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppTrigger(
        String name,
        String title,
        String description,
        List<String> params,
        Object paramsSchema
) {
}
