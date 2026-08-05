package ru.agimate.controlapi.controller.mcp.dto;

/** One block of a tool's answer. Only text is produced here — tools return JSON, not media. */
public record ToolContent(String type, String text) {

    public static ToolContent text(String text) {
        return new ToolContent("text", text);
    }
}
