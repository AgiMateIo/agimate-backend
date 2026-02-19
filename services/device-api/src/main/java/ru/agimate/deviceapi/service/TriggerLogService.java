package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.controller.app.dto.TriggerRequest;
import ru.agimate.deviceapi.controller.manage.dto.TriggerLogResponse;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.entities.TriggerLog;
import ru.agimate.deviceapi.database.repositories.TriggerLogRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TriggerLogService {

    private final TriggerLogRepository triggerLogRepository;

    public Page<TriggerLogResponse> getTriggerLogs(UUID userPubId, String deviceId, UUID appPubId, int page, int size) {
        return triggerLogRepository.findByUserPubIdWithFilters(userPubId, deviceId, appPubId, PageRequest.of(page, size))
                .map(TriggerLogResponse::from);
    }


    @Transactional
    public TriggerLog.TriggerLogBuilder getTriggerLogBuilder(App app, TriggerRequest triggerRequest) {
        String linkedDeviceId = null;
        if (app.isLinked()) {
            linkedDeviceId = app.getDeviceId();
            if (!Objects.equals(linkedDeviceId, triggerRequest.deviceId())) {
                log.warn("Device ID mismatch: request deviceId='{}', linked deviceId='{}', appId={}",
                        triggerRequest.deviceId(), linkedDeviceId, app.getId());
            }
        } else {
            log.warn("Trigger from not linked app: request deviceId='{}', appId={}",
                    triggerRequest.deviceId(), app.getId());
        }

        var triggerData = JsonUtils.fromJsonToMap(triggerRequest.data().toString());

        return TriggerLog.builder()
                .app(app)
                .userPubId(app.getUserPubId())
                .triggerId(triggerRequest.id())
                .triggerType(triggerRequest.type())
                .triggerName(triggerRequest.name())
                .triggerSource(triggerRequest.source())
                .requestDeviceId(triggerRequest.deviceId())
                .linkedDeviceId(linkedDeviceId)
                .occurredAt(triggerRequest.occurredAt() != null
                        ? LocalDateTime.ofInstant(triggerRequest.occurredAt(), ZoneOffset.UTC)
                        : null)
                .triggerData(triggerData);
    }

    @Transactional
    public TriggerLog logTrigger(TriggerLog.TriggerLogBuilder triggerLogBuilder) {
        return triggerLogRepository.save(triggerLogBuilder.build());
    }
}
