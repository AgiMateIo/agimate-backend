package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One extra HTTP header the connection form asks for")
public record HeaderFieldResponse(
        @Schema(description = "HTTP header name")
        String name,

        @Schema(description = "Display label in the requested language")
        String label,

        @Schema(description = "Whether the value is masked")
        boolean secret,

        @Schema(description = "Whether an empty value is rejected")
        boolean required,

        @Schema(description = "Prefix the client prepends to the entered value")
        String valuePrefix,

        @Schema(description = "Part of the credential the user enters; shown as a hint, not prepended")
        String keyPrefix
) {
}
