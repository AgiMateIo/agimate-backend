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
import ru.agimate.deviceapi.database.entities.Connector;
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

    public Page<TriggerLogResponse> getTriggerLogs(UUID userPubId, String deviceId, UUID connectorPubId, int page, int size) {
        return triggerLogRepository.findByUserPubIdWithFilters(userPubId, deviceId, connectorPubId, PageRequest.of(page, size))
                .map(TriggerLogResponse::from);
    }


    @Transactional
    public TriggerLog.TriggerLogBuilder getTriggerLogBuilder(Connector connector, TriggerRequest triggerRequest) {
        String linkedDeviceId = null;
        if (connector.isLinked()) {
            linkedDeviceId = connector.getDeviceId();
            if (!Objects.equals(linkedDeviceId, triggerRequest.deviceId())) {
                log.warn("Device ID mismatch: request deviceId='{}', linked deviceId='{}', connectorId={}",
                        triggerRequest.deviceId(), linkedDeviceId, connector.getId());
            }
        } else {
            log.warn("Trigger from not linked connector: request deviceId='{}', connectorId={}",
                    triggerRequest.deviceId(), connector.getId());
        }

        var triggerData = JsonUtils.fromJsonToMap(triggerRequest.data().toString());

        return TriggerLog.builder()
                .connector(connector)
                .userPubId(connector.getUserPubId())
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
