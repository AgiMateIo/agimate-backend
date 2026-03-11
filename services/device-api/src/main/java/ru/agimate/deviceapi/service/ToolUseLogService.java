package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.controller.manage.dto.ToolUseLogResponse;
import ru.agimate.deviceapi.abac.AccessEffect;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.entities.ToolUseLog;
import ru.agimate.deviceapi.database.repositories.ToolUseLogRepository;
import ru.agimate.deviceapi.service.dto.IToolResult;
import ru.agimate.deviceapi.service.dto.IToolUse;

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
    public ToolUseLog createLog(Agent agent, IToolUse toolUse, String agentSessionId,
                                AccessEffect effect, String error) {
        var toolUseLog = ToolUseLog.builder()
                .agentPubId(agent.getPubId())
                .userPubId(agent.getUserPubId())
                .connectorCode(toolUse.getConnectorCode())
                .identity(toolUse.getIdentity())
                .toolUseId(toolUse.getId())
                .toolName(toolUse.getName())
                .input(toolUse.getInput())
                .agentSessionId(agentSessionId)
                .accessEffect(effect)
                .error(error)
                .build();

        return toolUseLogRepository.save(toolUseLog);
    }

    @Transactional
    public ToolUseLog recordOutput(App app, IToolResult toolResult) {
        var toolUseLog = toolUseLogRepository.findByToolUseId(toolResult.getId())
                .orElseThrow(() -> new NotFoundStatusException("ToolUseLog", toolResult.getId()));

        if (!app.getPubId().toString().equals(toolUseLog.getIdentity())) {
            throw new ForbiddenStatusException("Incorrect device");
        }

        toolUseLog.setOutputAt(LocalDateTime.now());
        toolUseLog.setOutput(toolResult.getOutput());
        toolUseLog.setError(toolResult.getError());

        return toolUseLogRepository.save(toolUseLog);
    }

    @Transactional
    public ToolUseLog recordOutput(IToolResult toolResult) {
        var toolUseLog = toolUseLogRepository.findByToolUseId(toolResult.getId())
                .orElseThrow(() -> new NotFoundStatusException("ToolUseLog", toolResult.getId()));

        toolUseLog.setOutputAt(LocalDateTime.now());
        toolUseLog.setOutput(toolResult.getOutput());
        toolUseLog.setError(toolResult.getError());

        return toolUseLogRepository.save(toolUseLog);
    }

    @Transactional
    public ToolUseLog recordOutputByAgent(UUID agentPubId, IToolResult toolResult) {
        var toolUseLog = toolUseLogRepository.findByToolUseId(toolResult.getId())
                .orElseThrow(() -> new NotFoundStatusException("ToolUseLog", toolResult.getId()));

        if (!agentPubId.equals(toolUseLog.getAgentPubId())) {
            throw new ForbiddenStatusException("ToolUseLog does not belong to this agent");
        }

        if (toolUseLog.getAccessEffect() != AccessEffect.ALLOW) {
            throw new ForbiddenStatusException("Cannot record output for denied tool use");
        }

        toolUseLog.setOutputAt(LocalDateTime.now());
        toolUseLog.setOutput(toolResult.getOutput());
        toolUseLog.setError(toolResult.getError());

        return toolUseLogRepository.save(toolUseLog);
    }

    public Page<ToolUseLogResponse> getToolUseLogs(UUID userPubId, UUID agentPubId, int page, int size) {
        return toolUseLogRepository.findWithFilters(userPubId, agentPubId, PageRequest.of(page, size, Sort.by("createdAt").descending()))
                .map(ToolUseLogResponse::from);
    }
}
