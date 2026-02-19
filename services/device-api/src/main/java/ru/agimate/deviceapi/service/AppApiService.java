package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.controller.manage.dto.DeviceTriggersResponse;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.repositories.AppRepository;
import ru.agimate.deviceapi.service.dto.ConnectedDevice;
import ru.agimate.deviceapi.service.dto.DeviceTool;
import ru.agimate.deviceapi.service.dto.DeviceTrigger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AppApiService {

    private final AppRepository appRepository;
    private final CentrifugoService centrifugoService;

    public List<ConnectedDevice> getApps(String userId) {
        return appRepository.findByPubIdNotDeletedAndActive(UUID.fromString(userId))
                .stream().map(app -> new ConnectedDevice(
                        app.getPubId().toString(),
                        app.getName(),
                        app.getDescription()
                ))
                .toList();
    }

    public App getAppByPubId(String appPubId) {
        return appRepository.findByPubIdNotDeleted(UUID.fromString(appPubId))
                .orElseThrow(() -> new IllegalStateException("App " + appPubId + " is not found"));
    }

    @SuppressWarnings("unchecked")
    public List<DeviceTriggersResponse> getAllAppTriggers(UUID userPubId) {
        return appRepository.findLinkedByUserPubId(userPubId).stream()
                .map(app -> {
                    var triggers = app.getTriggers();
                    List<DeviceTrigger> triggerList;
                    if (triggers == null) {
                        triggerList = List.of();
                    } else {
                        triggerList = triggers.entrySet().stream()
                                .map(entry -> {
                                    var value = (Map<String, Object>) entry.getValue();
                                    var description = value.getOrDefault("description", "").toString();
                                    return new DeviceTrigger(entry.getKey(), description);
                                })
                                .toList();
                    }
                    return new DeviceTriggersResponse(
                            app.getPubId().toString(),
                            app.getDeviceId(),
                            getDeviceName(app),
                            triggerList
                    );
                })
                .toList();
    }

    public List<DeviceTrigger> getTriggers(String appId) {
        return List.of(new DeviceTrigger("shaked", "If device shaked"));
    }

    @SuppressWarnings("unchecked")
    public List<DeviceTool> getTools(String appPubId) {
        var app = getAppByPubId(appPubId);
        var tools = app.getTools();
        if (tools == null) {
            return List.of();
        }
        return tools.entrySet().stream()
                .map(entry -> {
                    var value = (Map<String, Object>) entry.getValue();
                    var params = value.get("params") instanceof List<?> list
                            ? list.stream().map(Object::toString).toList()
                            : List.<String>of();
                    return new DeviceTool(entry.getKey(), params);
                })
                .toList();
    }

    public void pushToDevice(String appPubId, IToolUse toolUse) {
        var app = getAppByPubId(appPubId);
        var channel = "device:" + app.getDeviceId();
        centrifugoService.publishMessage(channel, toolUse);
    }

    public void pushToAgent(String agentId, IToolResult toolResult) {
        var channel = "agent:" + agentId;
        centrifugoService.publishMessage(channel, toolResult);
    }

    @SuppressWarnings("unchecked")
    private String getDeviceName(App app) {
        if (app.getDeviceFeatures() == null) return null;
        Object name = app.getDeviceFeatures().get("deviceName");
        return name != null ? name.toString() : null;
    }
}
