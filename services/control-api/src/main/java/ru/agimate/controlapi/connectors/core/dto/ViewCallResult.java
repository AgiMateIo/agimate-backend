package ru.agimate.controlapi.connectors.core.dto;

import java.util.List;
import java.util.Map;

/**
 * An MCP {@code CallToolResult} for a view.
 *
 * @param structuredContent {@code null} when the tool produced none
 */
public record ViewCallResult(List<Object> content, Object structuredContent, boolean isError) {

    public static ViewCallResult text(String text) {
        return new ViewCallResult(List.of(Map.of("type", "text", "text", text)), null, false);
    }

    public static ViewCallResult error(String message) {
        return new ViewCallResult(List.of(Map.of("type", "text", "text", message)), null, true);
    }
}
