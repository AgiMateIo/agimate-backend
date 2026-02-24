package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.controller.manage.dto.DeviceToolsResponse;
import ru.agimate.deviceapi.controller.manage.dto.DeviceTriggersResponse;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.entities.AppType;
import ru.agimate.deviceapi.database.repositories.AppRepository;
import ru.agimate.deviceapi.database.repositories.IntegrationRepository;
import ru.agimate.deviceapi.integration.IntegrationToolExecutorService;
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
    private final IntegrationRepository integrationRepository;
    private final IntegrationToolExecutorService integrationToolExecutorService;

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

    public List<DeviceTriggersResponse> getAllAppTriggers(UUID userPubId) {
        return appRepository.findWithCapabilitiesByUserPubId(userPubId).stream()
                .map(app -> new DeviceTriggersResponse(
                        app.getPubId().toString(),
                        app.getDeviceId(),
                        getDeviceName(app),
                        parseTriggers(app.getTriggers())
                ))
                .toList();
    }

    public List<DeviceToolsResponse> getAllAppTools(UUID userPubId) {
        return appRepository.findWithCapabilitiesByUserPubId(userPubId).stream()
                .map(app -> new DeviceToolsResponse(
                        app.getPubId().toString(),
                        app.getDeviceId(),
                        getDeviceName(app),
                        parseTools(app.getTools())
                ))
                .toList();
    }

    public List<DeviceTrigger> getTriggers(String appId) {
        return List.of(new DeviceTrigger("shaked", "If device shaked", List.of()));
    }

    public List<DeviceTool> getTools(String appPubId) {
        var app = getAppByPubId(appPubId);
        return parseTools(app.getTools());
    }

    public void pushToApp(String appPubId, IToolUse toolUse, String agentId) {
        var app = getAppByPubId(appPubId);

        if (app.getType() == AppType.INTEGRATION) {
            var integration = integrationRepository.findByAppId(app.getId())
                    .orElseThrow(() -> new IllegalStateException("Integration not found for app " + appPubId));
            integrationToolExecutorService.execute(integration, toolUse, agentId);
            return;
        }

        var channel = "device:" + app.getDeviceId();
        centrifugoService.publishMessage(channel, toolUse);
    }

    public void pushToAgent(String agentId, IToolResult toolResult) {
        var channel = "agent:" + agentId;
        centrifugoService.publishMessage(channel, toolResult);
    }

    @SuppressWarnings("unchecked")
    private List<DeviceTool> parseTools(Map<String, Object> tools) {
        if (tools == null) return List.of();
        return tools.entrySet().stream()
                .map(entry -> {
                    var value = (Map<String, Object>) entry.getValue();
                    var description = value.getOrDefault("description", "").toString();
                    var params = value.get("params") instanceof List<?> list
                            ? list.stream().map(Object::toString).toList()
                            : List.<String>of();
                    return new DeviceTool(entry.getKey(), description, params);
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<DeviceTrigger> parseTriggers(Map<String, Object> triggers) {
        if (triggers == null) return List.of();
        return triggers.entrySet().stream()
                .map(entry -> {
                    var value = (Map<String, Object>) entry.getValue();
                    var description = value.getOrDefault("description", "").toString();
                    var params = value.get("params") instanceof List<?> list
                            ? list.stream().map(Object::toString).toList()
                            : List.<String>of();
                    return new DeviceTrigger(entry.getKey(), description, params);
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    private String getDeviceName(App app) {
        if (app.getDeviceFeatures() == null) return null;
        Object name = app.getDeviceFeatures().get("deviceName");
        return name != null ? name.toString() : null;
    }
}
