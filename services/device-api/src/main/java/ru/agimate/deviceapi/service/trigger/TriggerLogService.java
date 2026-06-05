package ru.agimate.deviceapi.service.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    public Page<TriggerLogResponse> getTriggerLogs(UUID userId, String connectorCode, int page, int size) {
        return triggerLogRepository.findByUserIdWithFilters(userId, connectorCode, PageRequest.of(page, size, Sort.by("createdAt").descending()))
                .map(TriggerLogResponse::from);
    }

    @Transactional
    public TriggerLog createTriggerLog(UUID userId, Trigger trigger) {
        LocalDateTime occurredAt = null;
        if (trigger.occurredAt() != null && !trigger.occurredAt().isEmpty()) {
            occurredAt = LocalDateTime.ofInstant(java.time.Instant.parse(trigger.occurredAt()), ZoneOffset.UTC);
        }

        TriggerLog triggerLog = TriggerLog.builder()
                .userId(userId)
                .connectorCode(trigger.connectorCode())
                .identity(trigger.identity())
                .triggerId(trigger.id() != null ? trigger.id() : "")
                .triggerName(trigger.name())
                .occurredAt(occurredAt)
                .triggerInput(trigger.data())
                .build();

        return triggerLogRepository.save(triggerLog);
    }

    @Transactional
    public TriggerLog save(TriggerLog triggerLog) {
        return triggerLogRepository.save(triggerLog);
    }
}
