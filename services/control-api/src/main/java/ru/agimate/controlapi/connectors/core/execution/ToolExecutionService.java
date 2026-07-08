package ru.agimate.controlapi.connectors.core.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorContext;
import ru.agimate.controlapi.connectors.core.ConnectorContextFactory;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.controller.app.dto.ToolResultRequest;
import ru.agimate.controlapi.database.entities.ChannelSession;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.repositories.ChannelSessionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.service.AgentDeliveryService;
import ru.agimate.controlapi.service.tool.ToolCallLogService;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Асинхронное выполнение тулы коннектора по записи {@link ToolCallLog}: контекст собирается
 * по типу хендлера (integration — со свежими credentials по {@code identity}), результат
 * пишется в лог и доставляется агенту.
 *
 * <p>Ошибки не синхронны для вызывающего: отсутствие credentials и любые сбои выполнения
 * превращаются в error tool-result. Сообщения {@link ConnectorException} безопасны и
 * отдаются агенту как есть; остальное скрывается за общим "Tool execution failed".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolExecutionService {

    private final ConnectorRegistry connectorRegistry;
    private final ConnectionRepository connectionRepository;
    private final ChannelSessionRepository channelSessionRepository;
    private final ConnectorContextFactory contextFactory;
    private final ToolCallLogService toolCallLogService;
    private final AgentDeliveryService agentDeliveryService;

    @Async
    public void executeTool(ToolCallLog toolCallLog) {
        try {
            ConnectorHandler handler = connectorRegistry.getHandler(toolCallLog.getConnectorCode());
            ConnectorContext context = buildContext(handler, toolCallLog);

            Map<String, Object> result = handler.executeTool(
                    context, toolCallLog.getName(), toolCallLog.getInput());

            if (handler instanceof IntegrationConnectorHandler) {
                connectionRepository.updateLastUsedAt(
                        UUID.fromString(toolCallLog.getIdentity()), LocalDateTime.now());
            }

            deliver(toolCallLog, JsonUtils.writeValueAsString(result), null);
            log.debug("Executed tool '{}.{}'",
                    toolCallLog.getConnectorCode(), toolCallLog.getName());
        } catch (Exception e) {
            log.error("Failed to execute '{}.{}'",
                    toolCallLog.getConnectorCode(), toolCallLog.getName());
            String error = e instanceof ConnectorException ? e.getMessage() : "Tool execution failed";
            deliver(toolCallLog, null, error);
        }
    }

    private ConnectorContext buildContext(ConnectorHandler handler, ToolCallLog toolCallLog) {
        UUID channelId = resolveChannelId(toolCallLog.getAgentSessionId());
        if (handler instanceof IntegrationConnectorHandler) {
            Connection connection = connectionRepository
                    .findByIdAndUserIdNotDeleted(UUID.fromString(toolCallLog.getIdentity()), toolCallLog.getUserId())
                    .filter(Connection::isActive)
                    .orElseThrow(() -> new ConnectorException(
                            "Connection missing or disabled: " + toolCallLog.getIdentity()));
            return contextFactory.forConnection(connection, toolCallLog.getAgentId(), channelId);
        }
        return contextFactory.internal(
                toolCallLog.getIdentity(), toolCallLog.getUserId(), toolCallLog.getAgentId(), channelId);
    }

    /** Канал prompt-сессии, из которой пришёл вызов — доменный контекст для тулов; {@code null} вне канала. */
    private UUID resolveChannelId(String agentSessionId) {
        if (agentSessionId == null || agentSessionId.isBlank()) {
            return null;
        }
        try {
            return channelSessionRepository.findById(UUID.fromString(agentSessionId))
                    .map(ChannelSession::getChannelId)
                    .orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void deliver(ToolCallLog toolCallLog, String output, String error) {
        var toolResult = new ToolResultRequest(
                toolCallLog.getExternalId(), toolCallLog.getConnectorCode(), output, error);
        try {
            toolCallLogService.recordOutput(toolResult);
        } catch (Exception logError) {
            log.warn("Failed to log tool result: {}", logError.getMessage());
        }
        if (toolCallLog.getAgentId() != null) {
            agentDeliveryService.deliverToolResult(toolCallLog.getAgentId(), toolResult);
        }
    }
}
