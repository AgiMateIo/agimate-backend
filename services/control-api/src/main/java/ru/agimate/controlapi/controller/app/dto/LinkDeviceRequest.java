package ru.agimate.controlapi.controller.app.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record LinkDeviceRequest(
        @NotNull
        String deviceId,
        String deviceName,
        String deviceOs,
        Map<String, Object> deviceFeatures,
        Map<String, Object> triggers,
        Map<String, Object> tools,
        @Valid
        AppDeclaration app,
        @Valid
        SkillDeclaration skill
) {
}
