package ru.agimate.controlapi.service.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.controller.manage.dto.AgentRunResponse;
import ru.agimate.controlapi.controller.manage.dto.TriggerLogResponse;
import ru.agimate.controlapi.database.entities.TriggerLog;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.repositories.TriggerLogRepository;

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

    public Page<AgentRunResponse> getAgentRuns(UUID userId, UUID agentId, String connectorCode,
                                                         String connectionId, String name, RunStatus status,
                                                         int page, int size) {
        return triggerLogRepository.findAgentRunsWithFilters(
                        userId, agentId,
                        blankToNull(connectorCode), blankToNull(connectionId), blankToNull(name), status,
                        PageRequest.of(page, size))
                .map(AgentRunResponse::from);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
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
                .connectionId(trigger.connectionId())
                .externalId(trigger.id() != null ? trigger.id() : "")
                .name(trigger.name())
                .occurredAt(occurredAt)
                .input(trigger.data())
                .build();

        return triggerLogRepository.save(triggerLog);
    }

    @Transactional
    public TriggerLog save(TriggerLog triggerLog) {
        return triggerLogRepository.save(triggerLog);
    }
}
