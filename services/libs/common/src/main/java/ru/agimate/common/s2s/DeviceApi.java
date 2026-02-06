package ru.agimate.common.s2s;

import java.util.List;

public interface DeviceApi {

    List<ConnectedDevice> getDevices(String userId);

    List<DeviceTrigger> getTriggers(String deviceId);

    List<DeviceAction> getActions(String deviceId);

    void pushAction(String deviceAuthKeyId, Object data);
}
