package ru.agimate.mobileapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.agimate.mobileapi.database.repositories.DeviceAuthKeyRepository;
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
}
