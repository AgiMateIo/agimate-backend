package ru.agimate.connectorsapi.controller.api.dto.mobile;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to push action to mobile device")
public class MobileActionRequest {

    @Schema(
            description = "Action type",
            example = "tts",
            required = true
    )
    @NotNull(message = "Action type is required")
    private String type;

    @Schema(
            description = "Action parameter",
            example = "Hello world"
    )
    private String param;
}
