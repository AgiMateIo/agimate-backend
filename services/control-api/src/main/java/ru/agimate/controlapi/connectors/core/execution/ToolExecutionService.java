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
import ru.agimate.controlapi.database.entities.IntegrationCredentials;
import ru.agimate.controlapi.database.entities.ToolUseLog;
import ru.agimate.controlapi.database.repositories.IntegrationCredentialsRepository;
import ru.agimate.controlapi.service.AgentDeliveryService;
import ru.agimate.controlapi.service.tool.ToolUseLogService;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Асинхронное выполнение тулы коннектора по записи {@link ToolUseLog}: контекст собирается
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
    private final IntegrationCredentialsRepository integrationCredentialsRepository;
    private final ConnectorContextFactory contextFactory;
    private final ToolUseLogService toolUseLogService;
    private final AgentDeliveryService agentDeliveryService;

    @Async
    public void executeTool(ToolUseLog toolUseLog) {
        try {
            ConnectorHandler handler = connectorRegistry.getHandler(toolUseLog.getConnectorCode());
            ConnectorContext context = buildContext(handler, toolUseLog);

            Map<String, Object> result = handler.executeTool(
                    context, toolUseLog.getToolName(), toolUseLog.getInput());

            if (handler instanceof IntegrationConnectorHandler) {
                integrationCredentialsRepository.updateLastUsedAt(
                        UUID.fromString(toolUseLog.getIdentity()), LocalDateTime.now());
            }

            deliver(toolUseLog, JsonUtils.writeValueAsString(result), null);
            log.debug("Executed tool '{}' on connector {}",
                    toolUseLog.getToolName(), toolUseLog.getConnectorCode());
        } catch (Exception e) {
            log.error("Failed to execute tool '{}' on connector {}: {}",
                    toolUseLog.getToolName(), toolUseLog.getConnectorCode(), e.getMessage());
            String error = e instanceof ConnectorException ? e.getMessage() : "Tool execution failed";
            deliver(toolUseLog, null, error);
        }
    }

    private ConnectorContext buildContext(ConnectorHandler handler, ToolUseLog toolUseLog) {
        if (handler instanceof IntegrationConnectorHandler) {
            IntegrationCredentials credentials = integrationCredentialsRepository
                    .findByIdAndUserIdNotDeleted(UUID.fromString(toolUseLog.getIdentity()), toolUseLog.getUserId())
                    .filter(IntegrationCredentials::isActive)
                    .orElseThrow(() -> new ConnectorException(
                            "Integration credentials missing or disabled: " + toolUseLog.getIdentity()));
            return contextFactory.forIntegration(credentials, toolUseLog.getAgentId());
        }
        return contextFactory.internal(
                toolUseLog.getIdentity(), toolUseLog.getUserId(), toolUseLog.getAgentId());
    }

    private void deliver(ToolUseLog toolUseLog, String output, String error) {
        var toolResult = new ToolResultRequest(
                toolUseLog.getToolUseId(), toolUseLog.getConnectorCode(), output, error);
        try {
            toolUseLogService.recordOutput(toolResult);
        } catch (Exception logError) {
            log.warn("Failed to log tool result: {}", logError.getMessage());
        }
        if (toolUseLog.getAgentId() != null) {
            agentDeliveryService.deliverToolResult(toolUseLog.getAgentId(), toolResult);
        }
    }
}
