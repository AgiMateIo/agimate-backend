package ru.agimate.controlapi.controller.manage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.connectors.core.dto.ViewCallResult;

import java.util.List;

/** Handed to the view unchanged as the JSON-RPC {@code result} of its {@code tools/call}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "MCP CallToolResult")
public record ViewToolCallResponse(
        @Schema(description = "Content blocks")
        List<Object> content,

        @Schema(nullable = true, description = "Structured result, when the tool produced one")
        Object structuredContent,

        @JsonProperty("isError")
        @Schema(description = "Tool error, policy refusal or timeout — the view shows it, the transport stays 200")
        boolean isError
) {

    public static ViewToolCallResponse of(ViewCallResult result) {
        return new ViewToolCallResponse(result.content(), result.structuredContent(), result.isError());
    }
}
