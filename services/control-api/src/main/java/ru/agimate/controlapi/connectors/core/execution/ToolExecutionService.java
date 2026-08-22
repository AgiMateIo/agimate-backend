package ru.agimate.controlapi.connectors.core.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.AsyncTaskExecutor;
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
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.service.AgentDeliveryService;
import ru.agimate.controlapi.service.dto.ToolResult;
import ru.agimate.controlapi.service.tool.ToolCallLogService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private final AgentRunRepository agentRunRepository;
    private final AgentSessionRepository agentSessionRepository;
    private final ConnectorEnvFactory envFactory;
    private final ToolCallLogService toolCallLogService;
    private final AgentDeliveryService agentDeliveryService;
    /** The executor {@link #executeTool} runs on — a bounded wait needs a thread other than the caller's. */
    private final AsyncTaskExecutor toolExecutor;

    @Async("toolExecutor")
    public void executeTool(ToolCallLog toolCallLog) {
        ToolResult result = executeAndRecord(toolCallLog);
        if (toolCallLog.getAgentId() != null) {
            agentDeliveryService.deliverToolResult(toolCallLog, result);
        }
    }

    /**
     * Outcome of {@link #executeWithTimeout}: the budget either saw the result or ran out first.
     * A budget running out is not an error — the caller decides what a still-running call means
     * (a task handle, a timeout answer).
     */
    public sealed interface WaitOutcome {
        record Completed(ToolResult result) implements WaitOutcome {}
        /** The execution continues and will record its outcome in the log; the caller stopped waiting. */
        record StillRunning() implements WaitOutcome {}
    }

    /**
     * Runs the tool on the executor and waits at most {@code timeout} — for a caller holding an open
     * request. On timeout the execution is not cancelled: it runs to the end and records its outcome
     * in the log, the caller simply stops waiting.
     */
    public WaitOutcome executeWithTimeout(ToolCallLog toolCallLog, Duration timeout) {
        Future<ToolResult> execution = toolExecutor.submit(() -> executeAndRecord(toolCallLog));
        try {
            return new WaitOutcome.Completed(execution.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            log.info("Tool '{}.{}' did not finish in {}s — the caller stops waiting",
                    toolCallLog.getConnectorCode(), toolCallLog.getName(), timeout.toSeconds());
            return new WaitOutcome.StillRunning();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new WaitOutcome.Completed(error(toolCallLog, "Tool execution was interrupted"));
        } catch (ExecutionException e) {
            // executeAndRecord turns failures into an error result, so getting here means the executor itself broke
            log.error("Tool '{}.{}' failed outside execution",
                    toolCallLog.getConnectorCode(), toolCallLog.getName(), e.getCause());
            return new WaitOutcome.Completed(error(toolCallLog, "Tool execution failed"));
        }
    }

    /**
     * Runs the tool and writes the outcome to the log, then hands the result back instead of
     * delivering it. Blocks for as long as the connector takes — a caller that cannot wait forever
     * (an open HTTP request) has to bound it itself.
     */
    public ToolResult executeAndRecord(ToolCallLog toolCallLog) {
        // A call still queued on the pool has done nothing yet, unlike one already inside the connector.
        // Refused as an ordinary failed result, so the worker reads it like any other.
        if (cancelled(toolCallLog)) {
            log.info("Tool '{}.{}' skipped: run {} was cancelled",
                    toolCallLog.getConnectorCode(), toolCallLog.getName(), toolCallLog.getRunId());
            return record(toolCallLog, null, "Cancelled: the user stopped the run before this call started");
        }
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

    /** Outside a run ({@code run_id} null — an MCP call, a channel reply) there is nothing to cancel. */
    private boolean cancelled(ToolCallLog toolCallLog) {
        return toolCallLog.getRunId() != null
                && Boolean.TRUE.equals(agentRunRepository.isCancelRequested(toolCallLog.getRunId()));
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
        return agentSessionRepository.findById(sessionId)
                .map(AgentSession::getChannelId)
                .orElse(null);
    }

    /** A result that never reached the tool — nothing to record, the log keeps whatever the run writes. */
    private static ToolResult error(ToolCallLog toolCallLog, String message) {
        return new ToolResult(toolCallLog.getExternalId(), toolCallLog.getConnectorCode(), null, message);
    }

    /** A failure to persist the outcome must not swallow the outcome itself — the caller still gets it. */
    private ToolResult record(ToolCallLog toolCallLog, String output, String error) {
        var toolResult = new ToolResult(
                toolCallLog.getExternalId(), toolCallLog.getConnectorCode(), output, error);
        try {
            toolCallLogService.recordOutput(toolCallLog.getId(), toolResult);
        } catch (Exception logError) {
            log.warn("Failed to log tool result: {}", logError.getMessage());
        }
        return toolResult;
    }
}
