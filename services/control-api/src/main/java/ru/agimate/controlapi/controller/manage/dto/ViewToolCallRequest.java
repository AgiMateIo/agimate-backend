package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

@Schema(description = "A tools/call made by an open view, proxied by the host")
public record ViewToolCallRequest(
        @NotNull
        @Schema(description = "Connection of the open view — taken from the host's state, never from the view")
        UUID connectionId,

        @NotBlank
        @Schema(description = "Tool name exactly as the view sent it (the server's own, not namespaced)",
                example = "show-weather-dashboard")
        String name,

        @Schema(nullable = true, description = "Tool arguments as the view sent them")
        Map<String, Object> arguments
) {
}
