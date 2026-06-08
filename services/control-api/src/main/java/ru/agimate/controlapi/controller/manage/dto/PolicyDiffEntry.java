package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A single policy change entry")
public record PolicyDiffEntry(
        @Schema(description = "Policy type: TOOL or TRIGGER")
        String policyType,

        @Schema(description = "Connector code")
        String connectorCode,

        @Schema(description = "Tool or trigger name (null for connector-level policy)")
        String name
) {
}
