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
import ru.agimate.deviceapi.database.entities.TriggerLog;
import ru.agimate.deviceapi.database.repositories.TriggerLogRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TriggerLogService {

    private final TriggerLogRepository triggerLogRepository;

    public Page<TriggerLogResponse> getTriggerLogs(UUID userPubId, String connectorCode, int page, int size) {
        return triggerLogRepository.findByUserPubIdWithFilters(userPubId, connectorCode, PageRequest.of(page, size))
                .map(TriggerLogResponse::from);
    }

    @Transactional
    public TriggerLog createTriggerLog(UUID userPubId, String connectorCode, String identity, TriggerRequest triggerRequest) {
        var triggerInput = JsonUtils.fromJsonToMap(triggerRequest.data().toString());

        TriggerLog triggerLog = TriggerLog.builder()
                .userPubId(userPubId)
                .connectorCode(connectorCode)
                .identity(identity)
                .triggerId(triggerRequest.id() != null ? triggerRequest.id() : "")
                .triggerName(triggerRequest.name())
                .occurredAt(triggerRequest.occurredAt() != null
                        ? LocalDateTime.ofInstant(triggerRequest.occurredAt(), ZoneOffset.UTC)
                        : null)
                .triggerInput(triggerInput)
                .build();

        return triggerLogRepository.save(triggerLog);
    }
}
