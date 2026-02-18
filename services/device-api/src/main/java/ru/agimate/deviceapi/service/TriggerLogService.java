package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.controller.device.dto.TriggerRequest;
import ru.agimate.deviceapi.controller.manage.dto.TriggerLogResponse;
import ru.agimate.deviceapi.database.entities.DeviceAuthKey;
import ru.agimate.deviceapi.database.entities.TriggerLog;
import ru.agimate.deviceapi.database.repositories.TriggerLogRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TriggerLogService {

    private final TriggerLogRepository triggerLogRepository;

    public List<TriggerLogResponse> getTriggerLogs(UUID userPubId, String deviceId, UUID deviceAuthKeyId) {
        return triggerLogRepository.findByUserPubIdWithFilters(userPubId, deviceId, deviceAuthKeyId)
                .stream()
                .map(TriggerLogResponse::from)
                .toList();
    }

    @Transactional
    public TriggerLog logTrigger(DeviceAuthKey deviceAuthKey, TriggerRequest triggerRequest) {
        String linkedDeviceId = null;
        if (deviceAuthKey.getDevice() != null) {
            linkedDeviceId = deviceAuthKey.getDevice().getDeviceId();
            if (!Objects.equals(linkedDeviceId, triggerRequest.deviceId())) {
                log.warn("Device ID mismatch: request deviceId='{}', linked deviceId='{}', authKeyId={}",
                        triggerRequest.deviceId(), linkedDeviceId, deviceAuthKey.getId());
            }
        } else {
            log.warn("Trigger from not linked device: request deviceId='{}', authKeyId={}",
                    triggerRequest.deviceId(), deviceAuthKey.getId());
        }

        var triggerData = JsonUtils.fromJsonToMap(triggerRequest.data().toString());

        var triggerLog = TriggerLog.builder()
                .deviceAuthKey(deviceAuthKey)
                .userPubId(deviceAuthKey.getUserPubId())
                .triggerId(triggerRequest.id())
                .triggerType(triggerRequest.type())
                .triggerName(triggerRequest.name())
                .triggerSource(triggerRequest.source())
                .requestDeviceId(triggerRequest.deviceId())
                .linkedDeviceId(linkedDeviceId)
                .occurredAt(triggerRequest.occurredAt() != null
                        ? LocalDateTime.ofInstant(triggerRequest.occurredAt(), ZoneOffset.UTC)
                        : null)
                .triggerData(triggerData)
                .build();

        return triggerLogRepository.save(triggerLog);
    }
}
