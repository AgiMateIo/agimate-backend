package ru.agimate.deviceapi.controller.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to push action to device")
public class MobileActionRequest {

    @Schema(
            description = "Action type",
            example = "tts",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    @NotNull(message = "Action type is required")
    private String type;

    @Schema(
            description = "Action parameter",
            example = "Hello world"
    )
    private Map<String, Object> parameters;
}
