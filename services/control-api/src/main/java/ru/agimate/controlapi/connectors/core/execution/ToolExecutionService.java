package ru.agimate.controlapi.connectors.core.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvFactory;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.connectors.core.ToolProvider;
import ru.agimate.controlapi.database.entities.ChannelSession;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.repositories.ChannelSessionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.service.AgentDeliveryService;
import ru.agimate.controlapi.service.dto.ToolResult;
import ru.agimate.controlapi.service.tool.ToolCallLogService;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Асинхронное выполнение тулы коннектора по записи {@link ToolCallLog}: контекст собирается
 * по типу хендлера (integration — со свежими credentials по {@code connectionId}), результат
 * пишется в лог и доставляется агенту.
 *
 * <p>Ошибки не синхронны для вызывающего: отсутствие credentials и любые сбои выполнения
 * превращаются в error tool-result. Сообщения {@link ConnectorException} безопасны и
 * отдаются агенту как есть; остальное скрывается за общим "Tool execution failed".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutionService {

    private final ConnectorRegistry connectorRegistry;
    private final ConnectionRepository connectionRepository;
    private final ChannelSessionRepository channelSessionRepository;
    private final ConnectorEnvFactory envFactory;
    private final ToolCallLogService toolCallLogService;
    private final AgentDeliveryService agentDeliveryService;

    @Async("toolExecutor")
    public void executeTool(ToolCallLog toolCallLog) {
        try {
            ConnectorHandler handler = connectorRegistry.getHandler(toolCallLog.getConnectorCode());
            ToolProvider toolProvider = ConnectorRegistry.capability(handler, ToolProvider.class);
            ConnectorEnv env = buildEnv(handler, toolCallLog);

            Map<String, Object> result = toolProvider.executeTool(
                    env, toolCallLog.getName(), toolCallLog.getInput());

            if (handler instanceof IntegrationConnectorHandler) {
                connectionRepository.updateLastUsedAt(
                        UUID.fromString(toolCallLog.getConnectionId()), LocalDateTime.now());
            }

            deliver(toolCallLog, JsonUtils.writeValueAsString(result), null);
            log.debug("Executed tool '{}.{}'",
                    toolCallLog.getConnectorCode(), toolCallLog.getName());
        } catch (ConnectorException e) {
            // Ожидаемый сбой с безопасным сообщением (валидация, CAS-конфликт, нет connection, …) — отдаём агенту как есть
            log.warn("Tool '{}.{}' failed: {}",
                    toolCallLog.getConnectorCode(), toolCallLog.getName(), e.getMessage());
            deliver(toolCallLog, null, e.getMessage());
        } catch (Exception e) {
            // Непредвиденный сбой — сохраняем стектрейс в логе, агенту прячем детали
            log.error("Failed to execute '{}.{}'",
                    toolCallLog.getConnectorCode(), toolCallLog.getName(), e);
            deliver(toolCallLog, null, "Tool execution failed");
        }
    }

    private ConnectorEnv buildEnv(ConnectorHandler handler, ToolCallLog toolCallLog) {
        UUID sessionId = parseSessionId(toolCallLog.getAgentSessionId());
        UUID channelId = resolveChannelId(sessionId);
        if (handler instanceof IntegrationConnectorHandler) {
            Connection connection = connectionRepository
                    .findByIdAndUserIdNotDeleted(UUID.fromString(toolCallLog.getConnectionId()), toolCallLog.getUserId())
                    .filter(Connection::isActive)
                    .orElseThrow(() -> new ConnectorException(
                            "Connection missing or disabled: " + toolCallLog.getConnectionId()));
            return envFactory.forConnection(connection, toolCallLog.getAgentId(),
                    toolCallLog.getRunId(), channelId);
        }
        return envFactory.internal(toolCallLog.getConnectionId(), toolCallLog.getUserId(),
                toolCallLog.getAgentId(), toolCallLog.getRunId(), channelId, sessionId);
    }

    private static UUID parseSessionId(String agentSessionId) {
        if (agentSessionId == null || agentSessionId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(agentSessionId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Канал prompt-сессии, из которой пришёл вызов — доменный контекст для тулов; {@code null} вне канала. */
    private UUID resolveChannelId(UUID sessionId) {
        if (sessionId == null) {
            return null;
        }
        return channelSessionRepository.findById(sessionId)
                .map(ChannelSession::getChannelId)
                .orElse(null);
    }

    private void deliver(ToolCallLog toolCallLog, String output, String error) {
        var toolResult = new ToolResult(
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
