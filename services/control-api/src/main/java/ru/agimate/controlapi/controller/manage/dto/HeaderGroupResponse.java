package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Headers of which the client sends exactly one")
public record HeaderGroupResponse(
        @Schema(description = "Group id, stable within the preset")
        String id,

        @Schema(description = "Alternative header names; at least two")
        List<String> oneOf,

        @Schema(description = "Whether one of the headers is required")
        boolean required
) {
}
