package ru.agimate.deviceapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Schema(description = "Request to replace all skill-connector bindings")
public record ReplaceSkillConnectorsRequest(
        @NotNull
        @Valid
        @Schema(description = "List of connector bindings")
        List<SkillConnectorRequest> connectors
) {}
