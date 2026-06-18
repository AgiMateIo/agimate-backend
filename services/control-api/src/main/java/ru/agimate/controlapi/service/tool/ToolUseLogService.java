package ru.agimate.controlapi.service.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.manage.dto.ToolUseLogResponse;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.database.entities.ToolUseLog;
import ru.agimate.controlapi.database.repositories.ToolUseLogRepository;
import ru.agimate.controlapi.service.dto.IToolResult;
import ru.agimate.controlapi.service.dto.IToolUse;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ToolUseLogService {

    private final ToolUseLogRepository toolUseLogRepository;

    public Optional<ToolUseLog> findByToolUseIdAndAgentId(String toolUseId, UUID agentId) {
        return toolUseLogRepository.findByToolUseIdAndAgentId(toolUseId, agentId);
    }

    @Transactional
    public ToolUseLog createLog(Agent agent, IToolUse toolUse, String agentSessionId,
                                AccessEffect effect, String error) {
        var toolUseLog = ToolUseLog.builder()
                .agentId(agent.getId())
                .userId(agent.getUserId())
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
        var toolUseLog = getByToolUseId(toolResult.getId());

        if (!app.getId().toString().equals(toolUseLog.getIdentity())) {
            throw new ForbiddenStatusException("Incorrect device");
        }

        toolUseLog.applyResult(toolResult);
        return toolUseLogRepository.save(toolUseLog);
    }

    @Transactional
    public ToolUseLog recordOutput(IToolResult toolResult) {
        var toolUseLog = getByToolUseId(toolResult.getId());

        toolUseLog.applyResult(toolResult);
        return toolUseLogRepository.save(toolUseLog);
    }

    @Transactional
    public ToolUseLog recordOutputByAgent(UUID agentId, IToolResult toolResult) {
        var toolUseLog = getByToolUseId(toolResult.getId());
        verifyOwnedByAgent(toolUseLog, agentId);

        if (toolUseLog.getAccessEffect() != AccessEffect.ALLOW) {
            throw new ForbiddenStatusException("Cannot record output for denied tool use");
        }

        toolUseLog.applyResult(toolResult);
        return toolUseLogRepository.save(toolUseLog);
    }

    public ToolUseLog findByToolUseIdForAgent(String toolUseId, UUID agentId) {
        var toolUseLog = getByToolUseId(toolUseId);
        verifyOwnedByAgent(toolUseLog, agentId);
        return toolUseLog;
    }

    public Page<ToolUseLogResponse> getToolUseLogs(UUID userId, UUID agentId, int page, int size) {
        return toolUseLogRepository.findWithFilters(userId, agentId, PageRequest.of(page, size, Sort.by("createdAt").descending()))
                .map(ToolUseLogResponse::from);
    }

    private ToolUseLog getByToolUseId(String toolUseId) {
        return toolUseLogRepository.findByToolUseId(toolUseId)
                .orElseThrow(() -> new NotFoundStatusException("ToolUseLog", toolUseId));
    }

    private void verifyOwnedByAgent(ToolUseLog toolUseLog, UUID agentId) {
        if (!agentId.equals(toolUseLog.getAgentId())) {
            throw new ForbiddenStatusException("ToolUseLog does not belong to this agent");
        }
    }
}
