package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.service.dto.AppTool;
import ru.agimate.controlapi.service.dto.AppTrigger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Full app information including device features, triggers and tools")
public record UserAppDetailResponse(
        @Schema(description = "App public ID")
        UUID appId,

        @Schema(description = "App kind the client declared; empty until the first link with the app block")
        String code,

        @Schema(description = "Human-readable app title")
        String title,

        @Schema(description = "Client version")
        String version,

        @Schema(description = "Device ID")
        String deviceId,

        @Schema(description = "Device name")
        String deviceName,

        @Schema(description = "Device OS")
        String deviceOs,

        @Schema(description = "Device info (deviceName, deviceOs, etc.)")
        Map<String, Object> info,

        @Schema(description = "Whether a device is connected to this app")
        boolean connected,

        @Schema(description = "Triggers declared by the app")
        Map<String, Object> triggers,

        @Schema(description = "Tools declared by the app")
        Map<String, Object> tools
) {
    public static UserAppDetailResponse from(App app, List<AppTool> tools, List<AppTrigger> triggers) {
        var info = app.getInfo();
        var deviceName = info != null && info.get("deviceName") != null
                ? info.get("deviceName").toString() : null;
        var deviceOs = info != null && info.get("deviceOs") != null
                ? info.get("deviceOs").toString() : null;

        return new UserAppDetailResponse(
                app.getId(),
                app.getCode(),
                app.getTitle(),
                app.getVersion(),
                app.getDeviceId(),
                deviceName,
                deviceOs,
                info,
                app.isLinked(),
                triggerMap(triggers),
                toolMap(tools)
        );
    }

    private static Map<String, Object> toolMap(List<AppTool> tools) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (AppTool tool : tools) {
            Map<String, Object> descriptor = new LinkedHashMap<>();
            if (tool.title() != null) {
                descriptor.put("title", tool.title());
            }
            if (tool.description() != null && !tool.description().isBlank()) {
                descriptor.put("description", tool.description());
            }
            if (tool.params() != null && !tool.params().isEmpty()) {
                descriptor.put("params", tool.params());
            }
            if (tool.inputSchema() != null) {
                descriptor.put("inputSchema", tool.inputSchema());
            }
            if (tool.outputSchema() != null) {
                descriptor.put("outputSchema", tool.outputSchema());
            }
            if (tool.annotations() != null) {
                descriptor.put("annotations", tool.annotations());
            }
            result.put(tool.name(), descriptor);
        }
        return result;
    }

    private static Map<String, Object> triggerMap(List<AppTrigger> triggers) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (AppTrigger trigger : triggers) {
            Map<String, Object> descriptor = new LinkedHashMap<>();
            if (trigger.title() != null) {
                descriptor.put("title", trigger.title());
            }
            if (trigger.description() != null && !trigger.description().isBlank()) {
                descriptor.put("description", trigger.description());
            }
            if (trigger.params() != null && !trigger.params().isEmpty()) {
                descriptor.put("params", trigger.params());
            }
            if (trigger.paramsSchema() != null) {
                descriptor.put("paramsSchema", trigger.paramsSchema());
            }
            result.put(trigger.name(), descriptor);
        }
        return result;
    }
}
