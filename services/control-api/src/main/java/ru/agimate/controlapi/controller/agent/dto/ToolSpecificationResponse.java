package ru.agimate.controlapi.controller.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "Tool specification with name, description, and JSON Schema parameters")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolSpecificationResponse(
        @Schema(description = "Tool name")
        String name,

        @Schema(description = "Tool description")
        String description,

        @Schema(description = "Parameters JSON Schema")
        JsonSchemaResponse parameters
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JsonSchemaResponse(
            @Schema(description = "JSON Schema type (string, integer, number, boolean, array, object)")
            String type,

            @Schema(description = "Parameter description")
            String description,

            @Schema(description = "Object properties (for type=object)")
            Map<String, JsonSchemaResponse> properties,

            @Schema(description = "Required property names (for type=object)")
            List<String> required,

            @Schema(description = "Array item schema (for type=array)")
            JsonSchemaResponse items,

            @Schema(description = "Allowed enum values (for type=string with enum)")
            List<String> enumValues
    ) {}
}
