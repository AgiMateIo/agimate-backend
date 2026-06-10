package ru.agimate.controlapi.connectors.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * MCP-совместимое описание тула, отдаваемое коннекторным SPI ({@code getTools()}). Заменяет
 * langchain4j {@code ToolSpecification}: несёт не только name/description/схему параметров, но и
 * MCP-поля title / {@link #annotations} / {@code _meta} / {@code outputSchema}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConnectorToolSpec(
        String name,
        String title,
        String description,
        JsonSchema inputSchema,
        JsonSchema outputSchema,
        ToolAnnotationsSpec annotations,
        @JsonProperty("_meta") Map<String, String> meta
) {
}
