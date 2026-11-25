package ru.agimate.controlapi.controller.mcp.dto;

/** One {@code contents} entry of {@code resources/read}: the text and the URI it belongs to. */
public record ResourceContent(String uri, String mimeType, String text) {
}
