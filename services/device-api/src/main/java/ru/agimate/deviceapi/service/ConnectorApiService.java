package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.connectors.integrations.IntegrationToolExecutorService;
import ru.agimate.deviceapi.connectors.internal.ServerToolExecutorService;
import ru.agimate.deviceapi.controller.manage.dto.DeviceToolsResponse;
import ru.agimate.deviceapi.controller.manage.dto.DeviceTriggersResponse;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.database.repositories.AppRepository;
import ru.agimate.deviceapi.database.repositories.ConnectorRepository;
import ru.agimate.deviceapi.database.repositories.IntegrationCredentialsRepository;
import ru.agimate.deviceapi.service.dto.ConnectedDevice;
import ru.agimate.deviceapi.service.dto.DeviceTool;
import ru.agimate.deviceapi.service.dto.DeviceTrigger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorApiService {

    private final AppRepository appRepository;
    private final CentrifugoService centrifugoService;
    private final ConnectorRepository connectorRepository;
    private final IntegrationCredentialsRepository integrationCredentialsRepository;
    private final IntegrationToolExecutorService integrationToolExecutorService;
    private final ServerToolExecutorService serverToolExecutorService;

    public List<ConnectedDevice> getConnectors(UUID userId) {
        return appRepository.findByPubIdNotDeletedAndActive(userId)
                .stream().map(app -> new ConnectedDevice(
                        app.getPubId().toString(),
                        app.getName(),
                        app.getDescription()
                ))
                .toList();
    }

    public App getAppByPubId(String appPubId) {
        return appRepository.findByPubIdNotDeleted(UUID.fromString(appPubId))
                .orElseThrow(() -> new NotFoundStatusException("App not found"));
    }

    public List<DeviceTriggersResponse> getAllConnectorTriggers(UUID userPubId) {
        return appRepository.findWithCapabilitiesByUserPubId(userPubId).stream()
                .map(app -> new DeviceTriggersResponse(
                        app.getPubId().toString(),
                        app.getDeviceId(),
                        getDeviceName(app),
                        parseTriggers(app.getTriggers())
                ))
                .toList();
    }

    public List<DeviceToolsResponse> getAllConnectorTools(UUID userPubId) {
        return appRepository.findWithCapabilitiesByUserPubId(userPubId).stream()
                .map(app -> new DeviceToolsResponse(
                        app.getPubId().toString(),
                        app.getDeviceId(),
                        getDeviceName(app),
                        parseTools(app.getTools())
                ))
                .toList();
    }

    public List<DeviceTrigger> getTriggers(String connectorId) {
        return List.of(new DeviceTrigger("shaked", "If device shaked", List.of()));
    }

    public List<DeviceTool> getTools(String appPubId) {
        var app = getAppByPubId(appPubId);
        return parseTools(app.getTools());
    }

    public List<DeviceTool> getToolsByAppPubIdAndUser(UUID appPubId, UUID userPubId) {
        var app = appRepository.findByPubIdAndUserPubIdNotDeleted(appPubId, userPubId)
                .orElseThrow(() -> new NotFoundStatusException("App not found"));
        return parseTools(app.getTools());
    }

    public List<DeviceTrigger> getTriggersByAppPubIdAndUser(UUID appPubId, UUID userPubId) {
        var app = appRepository.findByPubIdAndUserPubIdNotDeleted(appPubId, userPubId)
                .orElseThrow(() -> new NotFoundStatusException("App not found"));
        return parseTriggers(app.getTriggers());
    }

    public void pushToConnector(UUID userPubId, String agentId, String connectorCode, String identity, IToolUse toolUse) {
        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));

        switch (connector.getType()) {
            case APP -> {
                var app = appRepository.findByPubIdAndUserPubIdNotDeleted(UUID.fromString(identity), userPubId)
                        .orElseThrow(() -> new NotFoundStatusException("App not found: " + identity));
                centrifugoService.publishMessage("device:" + app.getDeviceId(), toolUse);
            }
            case INTEGRATION -> {
                var credentials = integrationCredentialsRepository.findByPubIdAndUserPubIdNotDeleted(UUID.fromString(identity), userPubId)
                        .orElseThrow(() -> new NotFoundStatusException("Integration credentials not found: " + identity));
                integrationToolExecutorService.execute(credentials, toolUse, agentId);
            }
            case INTERNAL_SERVICE -> serverToolExecutorService.execute(toolUse, UUID.fromString(agentId), userPubId);
            case LOOPBACK -> log.warn("LOOPBACK connector called, ignoring. connectorCode={}, toolUse={}", connectorCode, toolUse.getName());
        }
    }

    public void pushToAgent(String agentId, IToolResult toolResult) {
        centrifugoService.publishMessage("agent:" + agentId, toolResult);
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
        if (app.getInfo() == null) return null;
        Object name = app.getInfo().get("deviceName");
        return name != null ? name.toString() : null;
    }
}
