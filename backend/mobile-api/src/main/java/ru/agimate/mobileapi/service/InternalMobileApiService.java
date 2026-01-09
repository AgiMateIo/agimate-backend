package ru.agimate.mobileapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.agimate.common.s2s.DeviceAction;
import ru.agimate.common.s2s.DeviceTrigger;
import ru.agimate.common.s2s.MobileApi;
import ru.agimate.common.s2s.ConnectedDevice;
import ru.agimate.mobileapi.database.entities.Device;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class InternalMobileApiService implements MobileApi {

    private final DevicesService devicesService;
    private final CentrifugoService centrifugoService;

    @Override
    public List<ConnectedDevice> getDevices(String userId) {
        return devicesService.getDevices(userId);
    }

    @Override
    public List<DeviceTrigger> getTriggers(String deviceId) {
        return List.of(new DeviceTrigger("shaked", "If device shaked"));
    }

    @Override
    public List<DeviceAction> getActions(String deviceId) {
        return List.of(new DeviceAction("tts", "test to speach", Map.of("title", "Title", "message", "Message")));
    }

    @Override
    public void pushAction(String deviceAuthKeyId, Object data) {
        var device = devicesService.getDeviceByDeviceAuthKey(deviceAuthKeyId);
        var channel = "device:" + device.getDeviceId() + ":actions";
        centrifugoService.publishMessage(channel, data);
    }
}
