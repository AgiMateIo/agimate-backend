package ru.agimate.controlapi.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Тул устройства для manage-UI (экран капабилити). Несёт полный дескриптор, задекларированный
 * устройством при link: {@code inputSchema}/{@code outputSchema}/{@code annotations} — сырой JSON
 * (произвольная JSON Schema, как у MCP), {@code null} если устройство их не прислало. {@code params}
 * — производный плоский список имён (явный {@code params} или ключи {@code properties} схемы) для
 * простого отображения. Агент получает эти же тулы богатой схемой из {@code connection_tools}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppTool(
        String name,
        String title,
        String description,
        List<String> params,
        Object inputSchema,
        Object outputSchema,
        Object annotations
) {
}
