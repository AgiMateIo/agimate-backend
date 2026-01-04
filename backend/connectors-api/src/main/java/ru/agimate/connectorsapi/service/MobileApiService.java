package ru.agimate.connectorsapi.service;

import org.springframework.stereotype.Service;
import ru.agimate.common.s2s.ConnectedDevice;
import ru.agimate.common.s2s.DeviceAction;
import ru.agimate.common.s2s.DeviceTrigger;
import ru.agimate.common.s2s.MobileApi;

import java.util.List;

@Service
public class MobileApiService implements MobileApi {

    @Override
    public List<ConnectedDevice> getDevices(String userId) {
        return List.of();
    }

    @Override
    public List<DeviceTrigger> getTriggers(String deviceId) {
        return List.of();
    }

    @Override
    public List<DeviceAction> getActions(String deviceId) {
        return List.of();
    }

    @Override
    public void pushAction(String deviceId, Object data) {

    }

}
