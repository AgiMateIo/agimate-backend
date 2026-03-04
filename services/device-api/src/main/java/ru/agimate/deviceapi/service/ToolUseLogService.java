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
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.enums.PermissionDecision;
import ru.agimate.deviceapi.database.entities.ToolUseLog;
import ru.agimate.deviceapi.database.repositories.ToolUseLogRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ToolUseLogService {

    private final ToolUseLogRepository toolUseLogRepository;

    public Optional<ToolUseLog> findByToolUseIdAndUserPubId(String toolUseId, UUID userPubId) {
        return toolUseLogRepository.findByToolUseIdAndUserPubId(toolUseId, userPubId);
    }

    @Transactional
    public ToolUseLog createLog(UUID apiKeyPubId, UUID userPubId, String connectorPubId,
                                IToolUse toolUse, String agentSessionId,
                                PermissionDecision permissionDecision, String error) {
        var toolUseLog = ToolUseLog.builder()
                .apiKeyPubId(apiKeyPubId)
                .userPubId(userPubId)
                .connectorPubId(connectorPubId)
                .toolUseId(toolUse.getId())
                .toolName(toolUse.getName())
                .toolParams(toolUse.getParams())
                .agentSessionId(agentSessionId)
                .permissionDecision(permissionDecision)
                .error(error)
                .build();

        return toolUseLogRepository.save(toolUseLog);
    }

    @Transactional
    public ToolUseLog recordResult(App app, String toolUseId, String result, String error) {
        var toolUseLog = toolUseLogRepository.findByToolUseId(toolUseId)
                .orElseThrow(() -> new NotFoundStatusException("ToolUseLog", toolUseId));

        if (!app.getPubId().toString().equals(toolUseLog.getConnectorPubId())) {
            throw new ForbiddenStatusException("Incorrect device");
        }

        toolUseLog.setResultAt(LocalDateTime.now());
        toolUseLog.setResult(result);
        toolUseLog.setError(error);

        return toolUseLogRepository.save(toolUseLog);
    }

    @Transactional
    public ToolUseLog recordResultByAgent(UUID apiKeyPubId, String toolUseId, String result, String error) {
        var toolUseLog = toolUseLogRepository.findByToolUseId(toolUseId)
                .orElseThrow(() -> new NotFoundStatusException("ToolUseLog", toolUseId));

        if (!apiKeyPubId.equals(toolUseLog.getApiKeyPubId())) {
            throw new ForbiddenStatusException("ToolUseLog does not belong to this agent");
        }

        if (toolUseLog.getPermissionDecision() != PermissionDecision.ALLOW) {
            throw new ForbiddenStatusException("Cannot record result for denied tool use");
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
