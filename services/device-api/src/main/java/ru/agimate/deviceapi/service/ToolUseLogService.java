package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.controller.manage.dto.ToolUseLogResponse;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.database.entities.ToolUseLog;
import ru.agimate.deviceapi.database.repositories.ToolUseLogRepository;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ToolUseLogService {

    private final ToolUseLogRepository toolUseLogRepository;

    @Transactional
    public ToolUseLog createLog(UUID apiKeyPubId, UUID userPubId, String connectorPubId, IToolUse toolUse) {
        var toolUseLog = ToolUseLog.builder()
                .apiKeyPubId(apiKeyPubId)
                .userPubId(userPubId)
                .connectorPubId(connectorPubId)
                .toolUseId(toolUse.getId())
                .toolName(toolUse.getName())
                .toolParams(toolUse.getParams())
                .build();

        return toolUseLogRepository.save(toolUseLog);
    }

    @Transactional
    public ToolUseLog recordResult(Connector connector, String toolUseId, String result, String error) {
        var toolUseLog = toolUseLogRepository.findByToolUseId(toolUseId)
                .orElseThrow(() -> new NotFoundStatusException("ToolUseLog", toolUseId));

        if (!connector.getPubId().toString().equals(toolUseLog.getConnectorPubId())) {
            throw new ForbiddenStatusException("Incorrect device");
        }

        toolUseLog.setResultAt(LocalDateTime.now());
        toolUseLog.setResult(result);
        toolUseLog.setError(error);

        return toolUseLogRepository.save(toolUseLog);
    }

    public Page<ToolUseLogResponse> getToolUseLogs(UUID userPubId, UUID apiKeyPubId, int page, int size) {
        return toolUseLogRepository.findWithFilters(userPubId, apiKeyPubId, PageRequest.of(page, size))
                .map(ToolUseLogResponse::from);
    }
}
