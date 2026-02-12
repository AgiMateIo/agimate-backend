package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.controller.dto.response.DeviceTriggersResponse;
import ru.agimate.deviceapi.service.dto.ConnectedDevice;
import ru.agimate.deviceapi.service.dto.DeviceAction;
import ru.agimate.deviceapi.service.dto.DeviceTrigger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InternalDeviceApiService {

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
    public List<DeviceAction> getActions(String deviceId) {
        var device = devicesService.getDeviceByDeviceAuthKey(deviceId);
        var actions = device.getActions();
        if (actions == null) {
            return List.of();
        }
        return actions.entrySet().stream()
                .map(entry -> {
                    var value = (Map<String, Object>) entry.getValue();
                    var params = value.get("params") instanceof List<?> list
                            ? list.stream().map(Object::toString).toList()
                            : List.<String>of();
                    return new DeviceAction(entry.getKey(), params);
                })
                .toList();
    }

    public void pushAction(String deviceAuthKeyId, Object data) {
        var device = devicesService.getDeviceByDeviceAuthKey(deviceAuthKeyId);
        var channel = "device:" + device.getDeviceId() + ":actions";
        centrifugoService.publishMessage(channel, data);
    }
}
