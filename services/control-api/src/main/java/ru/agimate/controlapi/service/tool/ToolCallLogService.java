package ru.agimate.controlapi.service.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.manage.dto.ToolCallLogResponse;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ToolCallLogRepository;
import ru.agimate.controlapi.service.dto.IToolResult;
import ru.agimate.controlapi.service.dto.IToolCall;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ToolCallLogService {

    private final ToolCallLogRepository toolCallLogRepository;
    private final ConnectionRepository connectionRepository;

    public Optional<ToolCallLog> findByExternalIdAndAgentId(String externalId, UUID agentId) {
        return toolCallLogRepository.findByExternalIdAndAgentId(externalId, agentId);
    }

    @Transactional
    public ToolCallLog createLog(Agent agent, IToolCall toolCall, String agentSessionId,
                                AccessEffect effect, String error) {
        var toolCallLog = ToolCallLog.builder()
                .agentId(agent.getId())
                .userId(agent.getUserId())
                .connectorCode(resolveConnectorCode(toolCall, agent.getUserId()))
                .connectionId(toolCall.getConnectionId())
                .externalId(toolCall.getId())
                .name(toolCall.getName())
                .input(toolCall.getInput())
                .agentSessionId(agentSessionId)
                .accessEffect(effect)
                .error(error)
                .build();

        return toolCallLogRepository.save(toolCallLog);
    }

    /**
     * Connector code необязателен в запросе (например generic-reply его не хранит) — выводим из
     * connection ({@code connections.connector_code}) по {@code connectionId}, когда не задан.
     */
    private String resolveConnectorCode(IToolCall toolCall, UUID userId) {
        String code = toolCall.getConnectorCode();
        if (code != null && !code.isBlank()) {
            return code;
        }
        String connectionId = toolCall.getConnectionId();
        if (connectionId == null || connectionId.isBlank()) {
            throw new BadRequestStatusException("connectorCode or connectionId is required");
        }
        return connectionRepository.findByIdAndUserIdNotDeleted(UUID.fromString(connectionId), userId)
                .map(Connection::getConnectorCode)
                .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + connectionId));
    }

    /**
     * Записать результат tool-вызова, пришедший от устройства (app). Устройство корреллирует по PK лога
     * ({@code tool_call_logs.id}, глобально уникален) — так результат однозначно привязывается к логу
     * даже когда на одном app сидят несколько агентов (у них {@code external_id} может совпадать).
     * Владение проверяется по {@code connectionId == app.id}.
     */
    @Transactional
    public ToolCallLog recordOutputFromDevice(App app, IToolResult toolResult) {
        UUID logId;
        try {
            logId = UUID.fromString(toolResult.getId());
        } catch (IllegalArgumentException e) {
            throw new BadRequestStatusException("Invalid tool call id: " + toolResult.getId());
        }

        var toolCallLog = toolCallLogRepository.findById(logId)
                .orElseThrow(() -> new NotFoundStatusException("ToolCallLog", toolResult.getId()));

        if (!app.getId().toString().equals(toolCallLog.getConnectionId())) {
            throw new ForbiddenStatusException("Incorrect device");
        }

        toolCallLog.applyResult(toolResult);
        return toolCallLogRepository.save(toolCallLog);
    }

    @Transactional
    public ToolCallLog recordOutput(IToolResult toolResult) {
        var toolCallLog = getByExternalId(toolResult.getId());

        toolCallLog.applyResult(toolResult);
        return toolCallLogRepository.save(toolCallLog);
    }

    @Transactional
    public ToolCallLog recordOutputByAgent(UUID agentId, IToolResult toolResult) {
        var toolCallLog = getByExternalId(toolResult.getId());
        verifyOwnedByAgent(toolCallLog, agentId);

        if (toolCallLog.getAccessEffect() != AccessEffect.ALLOW) {
            throw new ForbiddenStatusException("Cannot record output for denied tool use");
        }

        toolCallLog.applyResult(toolResult);
        return toolCallLogRepository.save(toolCallLog);
    }

    public ToolCallLog findByExternalIdForAgent(String externalId, UUID agentId) {
        var toolCallLog = getByExternalId(externalId);
        verifyOwnedByAgent(toolCallLog, agentId);
        return toolCallLog;
    }

    public Page<ToolCallLogResponse> getToolCallLogs(UUID userId, UUID agentId, int page, int size) {
        return toolCallLogRepository.findWithFilters(userId, agentId, PageRequest.of(page, size, Sort.by("createdAt").descending()))
                .map(ToolCallLogResponse::from);
    }

    private ToolCallLog getByExternalId(String externalId) {
        return toolCallLogRepository.findByExternalId(externalId)
                .orElseThrow(() -> new NotFoundStatusException("ToolCallLog", externalId));
    }

    private void verifyOwnedByAgent(ToolCallLog toolCallLog, UUID agentId) {
        if (!agentId.equals(toolCallLog.getAgentId())) {
            throw new ForbiddenStatusException("ToolCallLog does not belong to this agent");
        }
    }
}
