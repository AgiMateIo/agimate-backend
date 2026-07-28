package ru.agimate.controlapi.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A device's tool for the manage UI (the capabilities screen). It carries the full descriptor the
 * device declared at link time: {@code inputSchema}/{@code outputSchema}/{@code annotations} as raw
 * JSON (an arbitrary JSON Schema, as with MCP), {@code null} when the device did not send them.
 * {@code params} is a derived flat list of names (the explicit {@code params}, or the schema's
 * {@code properties} keys) for simple display. The agent receives these same tools with the rich
 * schema out of {@code connection_tools}.
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
