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
 * Execution of a connector's tool from a {@link ToolCallLog} record: the context is assembled
 * according to the handler's type (integration — with fresh credentials for the
 * {@code connectionId}), and the outcome is written to the log.
 *
 * <p>Two callers, one core. An agent that is pushed to gets {@link #executeTool} — the result travels
 * back over its transport, so the call itself returns nothing. A caller holding an open request (the
 * MCP tools/call) takes {@link #executeAndRecord} and answers with the result it gets. Delivery is
 * the only difference; the log is written the same way in both.
 *
 * <p>Failures never surface as exceptions: missing credentials and any execution failure turn into an
 * error tool result. {@link ConnectorException} messages are safe and are handed over as-is;
 * everything else hides behind a generic "Tool execution failed".
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
        ToolResult result = executeAndRecord(toolCallLog);
        if (toolCallLog.getAgentId() != null) {
            agentDeliveryService.deliverToolResult(toolCallLog.getAgentId(), result);
        }
    }

    /**
     * Runs the tool and writes the outcome to the log, then hands the result back instead of
     * delivering it. Blocks for as long as the connector takes — a caller that cannot wait forever
     * (an open HTTP request) has to bound it itself.
     */
    public ToolResult executeAndRecord(ToolCallLog toolCallLog) {
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

            log.debug("Executed tool '{}.{}'",
                    toolCallLog.getConnectorCode(), toolCallLog.getName());
            return record(toolCallLog, JsonUtils.writeValueAsString(result), null);
        } catch (ConnectorException e) {
            // An expected failure with a safe message (validation, a CAS conflict, no connection, …) — passed to the agent as-is
            log.warn("Tool '{}.{}' failed: {}",
                    toolCallLog.getConnectorCode(), toolCallLog.getName(), e.getMessage());
            return record(toolCallLog, null, e.getMessage());
        } catch (Exception e) {
            // An unexpected failure — the stack trace is kept in the log and the details are hidden from the agent
            log.error("Failed to execute '{}.{}'",
                    toolCallLog.getConnectorCode(), toolCallLog.getName(), e);
            return record(toolCallLog, null, "Tool execution failed");
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

    /** Channel of the prompt session the call came from — domain context for the tools; {@code null} outside a channel. */
    private UUID resolveChannelId(UUID sessionId) {
        if (sessionId == null) {
            return null;
        }
        return channelSessionRepository.findById(sessionId)
                .map(ChannelSession::getChannelId)
                .orElse(null);
    }

    /** A failure to persist the outcome must not swallow the outcome itself — the caller still gets it. */
    private ToolResult record(ToolCallLog toolCallLog, String output, String error) {
        var toolResult = new ToolResult(
                toolCallLog.getExternalId(), toolCallLog.getConnectorCode(), output, error);
        try {
            toolCallLogService.recordOutput(toolResult);
        } catch (Exception logError) {
            log.warn("Failed to log tool result: {}", logError.getMessage());
        }
        return toolResult;
    }
}
