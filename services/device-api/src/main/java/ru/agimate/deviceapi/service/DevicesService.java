package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.database.entities.Device;
import ru.agimate.deviceapi.database.entities.DeviceAuthKey;
import ru.agimate.deviceapi.database.repositories.DeviceAuthKeyRepository;
import ru.agimate.common.s2s.ConnectedDevice;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DevicesService {
    private final DeviceAuthKeyRepository deviceAuthKeyRepository;

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
}
