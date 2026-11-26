package ru.agimate.controlapi.controller.app.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
        SkillDeclaration skill,
        @Valid
        @Size(max = 32)
        Map<String, SkillDeclaration> skills
) {
    /** The pre-multiple-skills client shape; {@code skills} stays absent. */
    public LinkDeviceRequest(String deviceId, String deviceName, String deviceOs,
                             Map<String, Object> deviceFeatures, Map<String, Object> triggers,
                             Map<String, Object> tools, AppDeclaration app, SkillDeclaration skill) {
        this(deviceId, deviceName, deviceOs, deviceFeatures, triggers, tools, app, skill, null);
    }
}
