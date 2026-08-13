package ru.agimate.controlapi.controller.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Result of {@code tools/call}. A failed tool is still a successful JSON-RPC response with
 * {@code isError} — the model has to see the failure and decide what to do, and a transport error
 * would hide it.
 *
 * @param structuredContent set only for tools that declare an {@code outputSchema}; the same payload
 *                          stays in {@code content} as text, which is what clients without
 *                          structured support read
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCallResult(
        List<ToolContent> content,
        Map<String, Object> structuredContent,
        Boolean isError
) implements McpResult {

    public static ToolCallResult text(String text, Map<String, Object> structuredContent) {
        return new ToolCallResult(List.of(ToolContent.text(text)), structuredContent, null);
    }

    public static ToolCallResult error(String message) {
        return new ToolCallResult(List.of(ToolContent.text(message)), null, true);
    }
}
