package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.controller.manage.dto.DeviceToolsResponse;
import ru.agimate.deviceapi.controller.manage.dto.DeviceTriggersResponse;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.database.enums.ConnectorType;
import ru.agimate.deviceapi.database.repositories.AppRepository;
import ru.agimate.deviceapi.database.repositories.ConnectorRepository;
import ru.agimate.deviceapi.database.repositories.IntegrationCredentialsRepository;
import ru.agimate.deviceapi.connectors.integrations.IntegrationToolExecutorService;
import ru.agimate.deviceapi.service.dto.ConnectedDevice;
import ru.agimate.deviceapi.service.dto.DeviceTool;
import ru.agimate.deviceapi.service.dto.DeviceTrigger;
import ru.agimate.deviceapi.connectors.internal.ServerToolExecutorService;

import ru.agimate.common.rest.error.NotFoundStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    public void pushToConnector(String connectorCode, IToolUse toolUse, String agentId) {
        Connector connector = connectorRepository.findById(connectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + connectorCode));

        if (connector.getType() == ConnectorType.INTERNAL_SERVICE) {
            var app = appRepository.findByPubIdNotDeletedAndActive(connector.getUserPubId()).stream()
                    .filter(a -> connectorCode.equals(a.getConnectorCode()))
                    .findFirst()
                    .orElseThrow(() -> new NotFoundStatusException("App not found for connector: " + connectorCode));
            serverToolExecutorService.execute(app, toolUse, agentId);
            return;
        }

        if (connector.getType() == ConnectorType.INTEGRATION) {
            var integrationCredentials = integrationCredentialsRepository.findByConnectorCode(connectorCode)
                    .orElseThrow(() -> new NotFoundStatusException("Integration credentials not found"));
            integrationToolExecutorService.execute(integrationCredentials, toolUse, agentId);
            return;
        }

        // For APP type connectors, find the app and push via centrifugo
        var app = appRepository.findByPubIdNotDeletedAndActive(connector.getUserPubId()).stream()
                .filter(a -> connectorCode.equals(a.getConnectorCode()))
                .findFirst()
                .orElseThrow(() -> new NotFoundStatusException("App not found for connector: " + connectorCode));

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
        if (app.getInfo() == null) return null;
        Object name = app.getInfo().get("deviceName");
        return name != null ? name.toString() : null;
    }
}
