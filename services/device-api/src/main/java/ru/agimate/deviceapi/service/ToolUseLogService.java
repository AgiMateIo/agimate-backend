package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.controller.manage.dto.ToolUseLogResponse;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.entities.ToolUseLog;
import ru.agimate.deviceapi.database.repositories.ToolUseLogRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ToolUseLogService {

    private final ToolUseLogRepository toolUseLogRepository;

    @Transactional
    public ToolUseLog createLog(UUID apiKeyPubId, String deviceAuthKeyId, IToolUse toolUse) {
        var toolUseLog = ToolUseLog.builder()
                .apiKeyPubId(apiKeyPubId)
                .deviceAuthKeyId(deviceAuthKeyId)
                .toolUseId(toolUse.getId())
                .toolName(toolUse.getName())
                .toolParams(toolUse.getParams())
                .build();

        return toolUseLogRepository.save(toolUseLog);
    }

    @Transactional
    public ToolUseLog recordResult(App app, String toolUseId, String result, String error) {
        var toolUseLog = toolUseLogRepository.findByToolUseId(toolUseId)
                .orElseThrow(() -> new NotFoundStatusException("ToolUseLog", toolUseId));

        if (!app.getPubId().toString().equals(toolUseLog.getDeviceAuthKeyId())) {
            throw new ForbiddenStatusException("Incorrect device");
        }

        toolUseLog.setResultAt(LocalDateTime.now());
        toolUseLog.setResult(result);
        toolUseLog.setError(error);

        return toolUseLogRepository.save(toolUseLog);
    }

    public List<ToolUseLogResponse> getToolUseLogs(UUID apiKeyPubId) {
        return toolUseLogRepository.findWithFilters(apiKeyPubId)
                .stream()
                .map(ToolUseLogResponse::from)
                .toList();
    }
}
