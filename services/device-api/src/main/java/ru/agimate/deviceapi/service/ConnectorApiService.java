package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.controller.manage.dto.DeviceToolsResponse;
import ru.agimate.deviceapi.controller.manage.dto.DeviceTriggersResponse;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.database.enums.ConnectorType;
import ru.agimate.deviceapi.database.repositories.ConnectorRepository;
import ru.agimate.deviceapi.database.repositories.IntegrationRepository;
import ru.agimate.deviceapi.integration.IntegrationToolExecutorService;
import ru.agimate.deviceapi.service.dto.ConnectedDevice;
import ru.agimate.deviceapi.service.dto.DeviceTool;
import ru.agimate.deviceapi.service.dto.DeviceTrigger;
import ru.agimate.deviceapi.service.servertools.ServerToolExecutorService;

import ru.agimate.common.rest.error.NotFoundStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConnectorApiService {

    private final ConnectorRepository connectorRepository;
    private final CentrifugoService centrifugoService;
    private final IntegrationRepository integrationRepository;
    private final IntegrationToolExecutorService integrationToolExecutorService;
    private final ServerToolExecutorService serverToolExecutorService;

    public List<ConnectedDevice> getConnectors(UUID userId) {
        return connectorRepository.findByPubIdNotDeletedAndActive(userId)
                .stream().map(connector -> new ConnectedDevice(
                        connector.getPubId().toString(),
                        connector.getName(),
                        connector.getDescription()
                ))
                .toList();
    }

    public Connector getConnectorByPubId(String connectorPubId) {
        return connectorRepository.findByPubIdNotDeleted(UUID.fromString(connectorPubId))
                .orElseThrow(() -> new NotFoundStatusException("Connector not found"));
    }

    public List<DeviceTriggersResponse> getAllConnectorTriggers(UUID userPubId) {
        return connectorRepository.findWithCapabilitiesByUserPubId(userPubId).stream()
                .map(connector -> new DeviceTriggersResponse(
                        connector.getPubId().toString(),
                        connector.getDeviceId(),
                        getDeviceName(connector),
                        parseTriggers(connector.getTriggers())
                ))
                .toList();
    }

    public List<DeviceToolsResponse> getAllConnectorTools(UUID userPubId) {
        return connectorRepository.findWithCapabilitiesByUserPubId(userPubId).stream()
                .map(connector -> new DeviceToolsResponse(
                        connector.getPubId().toString(),
                        connector.getDeviceId(),
                        getDeviceName(connector),
                        parseTools(connector.getTools())
                ))
                .toList();
    }

    public List<DeviceTrigger> getTriggers(String connectorId) {
        return List.of(new DeviceTrigger("shaked", "If device shaked", List.of()));
    }

    public List<DeviceTool> getTools(String connectorPubId) {
        var connector = getConnectorByPubId(connectorPubId);
        return parseTools(connector.getTools());
    }

    public void pushToConnector(String connectorPubId, IToolUse toolUse, String agentId) {
        var connector = getConnectorByPubId(connectorPubId);

        if (connector.getType() == ConnectorType.SERVER) {
            serverToolExecutorService.execute(connector, toolUse, agentId);
            return;
        }

        if (connector.getType() == ConnectorType.OUTBOUND) {
            var integration = integrationRepository.findByConnectorId(connector.getId())
                    .orElseThrow(() -> new NotFoundStatusException("Integration not found"));
            integrationToolExecutorService.execute(integration, toolUse, agentId);
            return;
        }

        var channel = "device:" + connector.getDeviceId();
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
    private String getDeviceName(Connector connector) {
        if (connector.getDeviceFeatures() == null) return null;
        Object name = connector.getDeviceFeatures().get("deviceName");
        return name != null ? name.toString() : null;
    }
}
