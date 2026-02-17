package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.controller.manage.dto.DeviceTriggersResponse;
import ru.agimate.deviceapi.database.entities.Device;
import ru.agimate.deviceapi.database.entities.DeviceAuthKey;
import ru.agimate.deviceapi.database.repositories.DeviceAuthKeyRepository;
import ru.agimate.deviceapi.database.repositories.DeviceRepository;
import ru.agimate.deviceapi.service.dto.ConnectedDevice;
import ru.agimate.deviceapi.service.dto.DeviceTrigger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DevicesService {
    private final DeviceAuthKeyRepository deviceAuthKeyRepository;
    private final DeviceRepository deviceRepository;

    public List<ConnectedDevice> getDevices(String userId) {
        return deviceAuthKeyRepository.findByPubIdNotDeletedAndActive(UUID.fromString(userId))
                .stream().map(deviceAuthKey -> new ConnectedDevice(
                                deviceAuthKey.getPubId().toString(),
                                deviceAuthKey.getName(),
                                deviceAuthKey.getDescription()
                        )
                ).collect(Collectors.toList());
    }

    public Device getDeviceByDeviceAuthKey(String deviceAuthKeyId) {
        return deviceAuthKeyRepository.findByPubIdNotDeleted(UUID.fromString(deviceAuthKeyId))
                .map(DeviceAuthKey::getDevice)
                .orElseThrow(() -> new IllegalStateException("Device for auth key " + deviceAuthKeyId + " is not found"));
    }

    @SuppressWarnings("unchecked")
    public List<DeviceTriggersResponse> getAllDeviceTriggers(UUID userPubId) {
        return deviceRepository.findByUserPubId(userPubId).stream()
                .map(device -> {
                    var triggers = device.getTriggers();
                    List<DeviceTrigger> triggerList;
                    if (triggers == null) {
                        triggerList = List.of();
                    } else {
                        triggerList = triggers.entrySet().stream()
                                .map(entry -> {
                                    var value = (Map<String, Object>) entry.getValue();
                                    var description = value.getOrDefault("description", "").toString();
                                    return new DeviceTrigger(entry.getKey(), description);
                                })
                                .toList();
                    }
                    var authKeyPubId = device.getDeviceAuthKey() != null
                            ? device.getDeviceAuthKey().getPubId().toString()
                            : null;
                    return new DeviceTriggersResponse(
                            authKeyPubId,
                            device.getDeviceId(),
                            device.getName(),
                            triggerList
                    );
                })
                .toList();
    }
}
