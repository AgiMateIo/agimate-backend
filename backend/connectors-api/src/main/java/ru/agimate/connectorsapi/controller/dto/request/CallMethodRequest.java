package ru.agimate.connectorsapi.controller.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

@Schema(description = "Request to call a connector method")
public record CallMethodRequest(
        @NotNull
        @Schema(description = "Credential ID to use for the call")
        UUID credentialId,

        @Schema(description = "Method parameters")
        Map<String, Object> parameters
) {
    public CallMethodRequest {
        if (parameters == null) {
            parameters = Map.of();
        }
    }
}
