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
import ru.agimate.deviceapi.abac.AccessEffect;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.App;
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
    public ToolUseLog createLog(Agent agent, String connectorCode, String identity,
                                IToolUse toolUse, String agentSessionId,
                                AccessEffect effect, String error) {
        var toolUseLog = ToolUseLog.builder()
                .agentPubId(agent.getPubId())
                .userPubId(agent.getUserPubId())
                .connectorCode(connectorCode)
                .identity(identity)
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
    public ToolUseLog recordOutput(App app, String toolUseId, String output, String error) {
        var toolUseLog = toolUseLogRepository.findByToolUseId(toolUseId)
                .orElseThrow(() -> new NotFoundStatusException("ToolUseLog", toolUseId));

        if (!app.getPubId().toString().equals(toolUseLog.getIdentity())) {
            throw new ForbiddenStatusException("Incorrect device");
        }

        toolUseLog.setOutputAt(LocalDateTime.now());
        toolUseLog.setOutput(output);
        toolUseLog.setError(error);

        return toolUseLogRepository.save(toolUseLog);
    }

    @Transactional
    public ToolUseLog recordOutput(String toolUseId, String output, String error) {
        var toolUseLog = toolUseLogRepository.findByToolUseId(toolUseId)
                .orElseThrow(() -> new NotFoundStatusException("ToolUseLog", toolUseId));

        toolUseLog.setOutputAt(LocalDateTime.now());
        toolUseLog.setOutput(output);
        toolUseLog.setError(error);

        return toolUseLogRepository.save(toolUseLog);
    }

    @Transactional
    public ToolUseLog recordOutputByAgent(UUID agentPubId, String toolUseId, String output, String error) {
        var toolUseLog = toolUseLogRepository.findByToolUseId(toolUseId)
                .orElseThrow(() -> new NotFoundStatusException("ToolUseLog", toolUseId));

        if (!agentPubId.equals(toolUseLog.getAgentPubId())) {
            throw new ForbiddenStatusException("ToolUseLog does not belong to this agent");
        }

        if (toolUseLog.getAccessEffect() != AccessEffect.ALLOW) {
            throw new ForbiddenStatusException("Cannot record output for denied tool use");
        }

        toolUseLog.setOutputAt(LocalDateTime.now());
        toolUseLog.setOutput(output);
        toolUseLog.setError(error);

        return toolUseLogRepository.save(toolUseLog);
    }

    public Page<ToolUseLogResponse> getToolUseLogs(UUID userPubId, UUID agentPubId, int page, int size) {
        return toolUseLogRepository.findWithFilters(userPubId, agentPubId, PageRequest.of(page, size))
                .map(ToolUseLogResponse::from);
    }
}
