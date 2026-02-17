package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.controller.manage.dto.DeviceTriggersResponse;
import ru.agimate.deviceapi.service.dto.ConnectedDevice;
import ru.agimate.deviceapi.service.dto.DeviceTool;
import ru.agimate.deviceapi.service.dto.DeviceTrigger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeviceApiService {

    private final DevicesService devicesService;
    private final CentrifugoService centrifugoService;

    public List<ConnectedDevice> getDevices(String userId) {
        return devicesService.getDevices(userId);
    }

    public List<DeviceTrigger> getTriggers(String deviceId) {
        return List.of(new DeviceTrigger("shaked", "If device shaked"));
    }

    public List<DeviceTriggersResponse> getAllTriggers(String userId) {
        return devicesService.getAllDeviceTriggers(UUID.fromString(userId));
    }

    @SuppressWarnings("unchecked")
    public List<DeviceTool> getTools(String deviceId) {
        var device = devicesService.getDeviceByDeviceAuthKey(deviceId);
        var tools = device.getTools();
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

    public void pushTool(String deviceAuthKeyId, IToolUse toolUse) {
        var device = devicesService.getDeviceByDeviceAuthKey(deviceAuthKeyId);
        var channel = "device:" + device.getDeviceId();
        centrifugoService.publishMessage(channel, toolUse);
    }

    public void pushToolResult(String agentId, IToolResult toolResult) {
        var channel = "agent:" + agentId;
        centrifugoService.publishMessage(channel, toolResult);
    }
}
