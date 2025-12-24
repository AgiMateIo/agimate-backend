package ru.agimate.connectorsapi.controller.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of a connector method call")
public record CallResultResponse(
        @Schema(description = "Whether the call was successful")
        boolean success,

        @Schema(description = "Response data from the connector")
        Object data,

        @Schema(description = "Error message if the call failed")
        String error,

        @Schema(description = "Execution time in milliseconds")
        long durationMs
) {}
