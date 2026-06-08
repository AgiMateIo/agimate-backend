package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Preview of policy changes")
public record PolicyDiffResponse(
        @Schema(description = "Policies that will be created")
        List<PolicyDiffEntry> policiesToAdd,

        @Schema(description = "Policies that will be removed")
        List<PolicyDiffEntry> policiesToRemove
) {
}
