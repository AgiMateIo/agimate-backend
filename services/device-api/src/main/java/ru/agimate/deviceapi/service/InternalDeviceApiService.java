package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.service.dto.ConnectedDevice;
import ru.agimate.deviceapi.service.dto.DeviceAction;
import ru.agimate.deviceapi.service.dto.DeviceTrigger;

import java.util.List;
import java.util.Map;

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

    public List<DeviceAction> getActions(String deviceId) {
        return List.of(new DeviceAction("tts", "test to speach", Map.of("title", "Title", "message", "Message")));
    }

    public void pushAction(String deviceAuthKeyId, Object data) {
        var device = devicesService.getDeviceByDeviceAuthKey(deviceAuthKeyId);
        var channel = "device:" + device.getDeviceId() + ":actions";
        centrifugoService.publishMessage(channel, data);
    }
}
