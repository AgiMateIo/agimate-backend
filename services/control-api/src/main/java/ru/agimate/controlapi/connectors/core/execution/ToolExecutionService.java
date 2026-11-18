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
import ru.agimate.controlapi.connectors.core.ToolExecutionPolicy;
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
import java.util.Optional;
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
 * <p>Two entry points, one core. An agent that is pushed to gets {@link #executeTool} — the result
 * travels back over its transport, so the call itself returns nothing. A caller holding an open
 * request (the MCP tools/call) takes {@link #executeWithTimeout} and answers with the result it gets.
 * Delivery is the only difference; the log is written the same way in both.
 *
 * <p>A {@link ToolExecutionPolicy.Kind#NON_REPLAYABLE} tool is dispatched at most once: the executor
 * claims the log row before touching the connector, and a lost claim ends the attempt silently. Every
 * outcome after a won claim — success, cancellation, an execution error, a lost finalization — is
 * written by the same guarded UPDATE, and only the caller whose write changed the row delivers the
 * result. A late actual result therefore never overwrites the {@code unknown} recovery wrote.
 *
 * <p>Failures never surface as exceptions: missing credentials and any execution failure turn into an
 * error tool result. {@link ConnectorException} messages are safe and are handed over as-is;
 * everything else hides behind a generic "Tool execution failed".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolExecutionService {

    private static final String CANCELLED_ERROR =
            "Cancelled: the user stopped the run before this call started";

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
        executeAndRecord(toolCallLog).ifPresent(result -> {
            if (toolCallLog.getAgentId() != null) {
                agentDeliveryService.deliverToolResult(toolCallLog, result);
            }
        });
    }

    /**
     * Outcome of {@link #executeWithTimeout}: the budget either saw the result or ran out first.
     * A budget running out is not an error — the caller decides what a still-running call means
     * (a task handle, a timeout answer).
     */
    public sealed interface WaitOutcome {
        record Completed(ToolResult result) implements WaitOutcome {}
        /**
         * The execution continues and the log will carry its outcome (or it was already dispatched by
         * another attempt); the caller stopped waiting.
         */
        record StillRunning() implements WaitOutcome {}
    }

    /**
     * Runs the tool on the executor and waits at most {@code timeout} — for a caller holding an open
     * request. On timeout the execution is not cancelled: it runs to the end and records its outcome
     * in the log, the caller simply stops waiting.
     */
    public WaitOutcome executeWithTimeout(ToolCallLog toolCallLog, Duration timeout) {
        Future<Optional<ToolResult>> execution = toolExecutor.submit(() -> executeAndRecord(toolCallLog));
        try {
            return execution.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .<WaitOutcome>map(WaitOutcome.Completed::new)
                    .orElseGet(WaitOutcome.StillRunning::new);
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
     * Runs the tool and writes the outcome to the log. Empty means the call was dispatched (or
     * finalized) elsewhere: a non-replayable tool must not run a second time, so there is nothing to
     * record or deliver.
     */
    private Optional<ToolResult> executeAndRecord(ToolCallLog toolCallLog) {
        try {
            ConnectorHandler handler = connectorRegistry.getHandler(toolCallLog.getConnectorCode());
            ToolProvider toolProvider = ConnectorRegistry.capability(handler, ToolProvider.class);
            ToolExecutionPolicy policy = toolProvider.executionPolicy(
                    toolCallLog.getName(), toolCallLog.getInput());

            if (policy.kind() == ToolExecutionPolicy.Kind.NON_REPLAYABLE) {
                return executeNonReplayable(handler, toolProvider, toolCallLog, policy.executionTimeout());
            }
            return Optional.of(executeReplayable(handler, toolProvider, toolCallLog));
        } catch (ConnectorException e) {
            // An expected failure with a safe message (validation, a CAS conflict, no connection, …) — passed to the agent as-is
            log.warn("Tool '{}.{}' failed: {}",
                    toolCallLog.getConnectorCode(), toolCallLog.getName(), e.getMessage());
            return recordUnlessDispatched(toolCallLog, e.getMessage());
        } catch (Exception e) {
            // An unexpected failure — the stack trace is kept in the log and the details are hidden from the agent
            log.error("Failed to execute '{}.{}'",
                    toolCallLog.getConnectorCode(), toolCallLog.getName(), e);
            return recordUnlessDispatched(toolCallLog, "Tool execution failed");
        }
    }

    /**
     * A failure before the policy is known: the row may belong to a non-replayable call another
     * attempt has already claimed. That attempt owns the row — this one must neither write the
     * failure over a live result nor race it through CAS. If the owner is actually dead, recovery
     * finalizes the row as {@code unknown} at its deadline. Only an undispatched row takes the
     * record path; a failing dispatch-state read writes nothing at all.
     */
    private Optional<ToolResult> recordUnlessDispatched(ToolCallLog toolCallLog, String error) {
        boolean dispatched;
        try {
            dispatched = toolCallLogService.isDispatched(toolCallLog.getId());
        } catch (Exception e) {
            log.warn("Failed to read dispatch state of tool call {}", toolCallLog.getId());
            return Optional.empty();
        }
        if (dispatched) {
            // A duplicate replay must not finalize a row whose original attempt may still be running
            log.info("Tool call {} is dispatched — this attempt writes nothing", toolCallLog.getId());
            return Optional.empty();
        }
        return Optional.of(record(toolCallLog, null, error));
    }

    /** A reversible call: the pre-claim path — cancellation, env, provider, last-used, output. */
    private ToolResult executeReplayable(ConnectorHandler handler, ToolProvider toolProvider,
                                         ToolCallLog toolCallLog) {
        // A call still queued on the pool has done nothing yet, unlike one already inside the connector.
        // Refused as an ordinary failed result, so the worker reads it like any other.
        if (cancelled(toolCallLog)) {
            log.info("Tool '{}.{}' skipped: run {} was cancelled",
                    toolCallLog.getConnectorCode(), toolCallLog.getName(), toolCallLog.getRunId());
            return record(toolCallLog, null, CANCELLED_ERROR);
        }
        String output = JsonUtils.writeValueAsString(invoke(handler, toolProvider, toolCallLog));
        log.debug("Executed tool '{}.{}'", toolCallLog.getConnectorCode(), toolCallLog.getName());
        return record(toolCallLog, output, null);
    }

    /**
     * An irreversible call: the claim comes first, and a lost claim ends the attempt before any
     * cancellation check, env build or provider call. A replay must not write over a row another
     * attempt is running, let alone open a second connection. Every outcome after a won claim is
     * CAS-finalized; only the CAS winner's result is returned for delivery.
     */
    private Optional<ToolResult> executeNonReplayable(ConnectorHandler handler, ToolProvider toolProvider,
                                                      ToolCallLog toolCallLog, Duration executionTimeout) {
        try {
            if (!toolCallLogService.claimDispatch(toolCallLog.getId(), executionTimeout)) {
                log.info("Tool '{}.{}' already dispatched — replay skipped",
                        toolCallLog.getConnectorCode(), toolCallLog.getName());
                return Optional.empty();
            }
        } catch (Exception e) {
            // The claim is the gate: if it did not land, no row of ours exists to finalize or write.
            // Ids only: a JDBC message can carry SQL and its parameters.
            log.warn("Tool call {} (connector {}) dispatch claim failed",
                    toolCallLog.getId(), toolCallLog.getConnectorCode());
            return Optional.empty();
        }
        try {
            // Read only after the claim: a reply that lost it has no right to record "Cancelled" over
            // the attempt already running the command.
            if (cancelled(toolCallLog)) {
                log.info("Tool '{}.{}' cancelled after the claim",
                        toolCallLog.getConnectorCode(), toolCallLog.getName());
                return finish(toolCallLog, null, CANCELLED_ERROR);
            }
            if (!toolCallLogService.isUnfinished(toolCallLog.getId())) {
                // Finalized in the window between the claim and here — the row is not ours to write.
                log.info("Tool '{}.{}' was finalized after the claim — not started",
                        toolCallLog.getConnectorCode(), toolCallLog.getName());
                return Optional.empty();
            }
            Map<String, Object> result = executeProvider(handler, toolProvider, toolCallLog);
            updateLastUsedBestEffort(handler, toolCallLog);
            String output = JsonUtils.writeValueAsString(result);
            log.debug("Executed non-replayable tool '{}.{}'",
                    toolCallLog.getConnectorCode(), toolCallLog.getName());
            return finish(toolCallLog, output, null);
        } catch (ConnectorException e) {
            // The safe message still goes to the agent; the log keeps ids and the outcome class only
            log.warn("Tool call {} failed with {}",
                    toolCallLog.getId(), e.getClass().getSimpleName());
            return finish(toolCallLog, null, e.getMessage());
        } catch (Exception e) {
            // A provider exception can carry the command or its output — never log its message or trace
            log.error("Tool call {} failed with {}",
                    toolCallLog.getId(), e.getClass().getSimpleName());
            return finish(toolCallLog, null, "Tool execution failed");
        }
    }

    /**
     * The one exit for a non-replayable outcome: the guarded UPDATE decides who owns it. A lost or
     * failed write yields no result, so nothing is delivered — the stored outcome stands.
     */
    private Optional<ToolResult> finish(ToolCallLog toolCallLog, String output, String error) {
        var toolResult = new ToolResult(
                toolCallLog.getExternalId(), toolCallLog.getConnectorCode(), output, error);
        try {
            if (!toolCallLogService.finishNonReplayable(toolCallLog.getId(), toolResult)) {
                log.info("Tool '{}.{}' was finalized elsewhere — outcome not delivered",
                        toolCallLog.getConnectorCode(), toolCallLog.getName());
                return Optional.empty();
            }
        } catch (Exception e) {
            // Without a landed write this attempt cannot prove it owns the outcome, so it must not deliver
            log.warn("Failed to finalize tool call {}", toolCallLog.getId());
            return Optional.empty();
        }
        return Optional.of(toolResult);
    }

    /**
     * Replayable execution: env, tool and the integration's last-used stamp inside one try — a
     * bookkeeping failure here is an execution failure. The non-replayable path stamps last-used
     * best-effort instead, because its command has already run.
     */
    private Map<String, Object> invoke(ConnectorHandler handler, ToolProvider toolProvider,
                                       ToolCallLog toolCallLog) {
        Map<String, Object> result = executeProvider(handler, toolProvider, toolCallLog);
        updateLastUsed(handler, toolCallLog);
        return result;
    }

    private Map<String, Object> executeProvider(ConnectorHandler handler, ToolProvider toolProvider,
                                                ToolCallLog toolCallLog) {
        ConnectorEnv env = buildEnv(handler, toolCallLog);
        return toolProvider.executeTool(env, toolCallLog.getName(), toolCallLog.getInput());
    }

    /**
     * On the non-replayable path the command has already run: a bookkeeping failure must not turn a
     * successful command into an error, so it is logged by id and swallowed.
     */
    private void updateLastUsedBestEffort(ConnectorHandler handler, ToolCallLog toolCallLog) {
        try {
            updateLastUsed(handler, toolCallLog);
        } catch (Exception e) {
            log.warn("Failed to update last-used for connection {}", toolCallLog.getConnectionId());
        }
    }

    private void updateLastUsed(ConnectorHandler handler, ToolCallLog toolCallLog) {
        if (handler instanceof IntegrationConnectorHandler) {
            connectionRepository.updateLastUsedAt(
                    UUID.fromString(toolCallLog.getConnectionId()), LocalDateTime.now());
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
