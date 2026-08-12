package ru.agimate.controlapi.service.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.manage.dto.ToolCallLogResponse;
import ru.agimate.controlapi.controller.manage.dto.ToolCallStatus;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ToolCallLogRepository;
import ru.agimate.controlapi.service.dto.IToolResult;
import ru.agimate.controlapi.service.dto.IToolCall;

import java.time.LocalDateTime;
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
                                String runId, AccessEffect effect, String error) {
        var toolCallLog = ToolCallLog.builder()
                .agentId(agent.getId())
                .userId(agent.getUserId())
                .connectorCode(resolveConnectorCode(toolCall, agent.getUserId()))
                .connectionId(toolCall.getConnectionId())
                .externalId(toolCall.getId())
                .name(toolCall.getName())
                .input(toolCall.getInput())
                .agentSessionId(agentSessionId)
                .runId(parseUuidOrNull(runId))
                .accessEffect(effect)
                .error(error)
                .build();

        return toolCallLogRepository.save(toolCallLog);
    }

    /** The initiating run from the worker's string run_id; a non-UUID or empty value → null (the tool is outside a run). */
    private static UUID parseUuidOrNull(String runId) {
        if (runId == null || runId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(runId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The connector code is optional in the request (a generic reply, for instance, does not store it) —
     * when absent we derive it from the connection ({@code connections.connector_code}) by
     * {@code connectionId}.
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
     * Record a tool call's result that arrived from an app. The app correlates by the log's PK
     * ({@code tool_call_logs.id}, globally unique) — so the result is unambiguously tied to its log even
     * when several agents share one app (their {@code external_id}s may coincide). Ownership is checked
     * by {@code connectionId == app.id}.
     */
    @Transactional
    public ToolCallLog recordOutputFromApp(App app, IToolResult toolResult) {
        UUID logId;
        try {
            logId = UUID.fromString(toolResult.getId());
        } catch (IllegalArgumentException e) {
            throw new BadRequestStatusException("Invalid tool call id: " + toolResult.getId());
        }

        var toolCallLog = toolCallLogRepository.findById(logId)
                .orElseThrow(() -> new NotFoundStatusException("ToolCallLog", toolResult.getId()));

        if (!app.getId().toString().equals(toolCallLog.getConnectionId())) {
            throw new ForbiddenStatusException("Incorrect app");
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

    /**
     * The worker stops waiting for the call: stamps {@code detached_at} unless the call finished
     * first. Returns the row after the attempt — the caller reads the outcome off it:
     * {@code detachedAt != null} means detached (fresh or an idempotent replay), otherwise
     * {@code finishAt} is set and the result is handed back instead. The guarded UPDATE plus the
     * row lock it takes settle the race with a concurrent {@code recordOutput}; both fields are
     * monotonic, so the re-read cannot contradict the stamp.
     */
    @Transactional
    public ToolCallLog detach(UUID agentId, String externalId) {
        toolCallLogRepository.markDetached(agentId, externalId, LocalDateTime.now());
        return toolCallLogRepository.findByExternalIdAndAgentId(externalId, agentId)
                .orElseThrow(() -> new NotFoundStatusException("ToolCallLog", externalId));
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

    public Page<ToolCallLogResponse> getToolCallLogs(UUID userId, UUID agentId, String connectorCode,
                                                     String connectionId, AccessEffect accessEffect,
                                                     String name, ToolCallStatus status, int page, int size) {
        var pageable = PageRequest.of(page, size);
        return toolCallLogRepository.findWithFilters(
                        userId, agentId,
                        blankToNull(connectorCode), blankToNull(connectionId),
                        accessEffect, blankToNull(name),
                        status != null ? status.name() : null,
                        pageable)
                .map(ToolCallLogResponse::from);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
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
