package ru.agimate.controlapi.connectors.core.execution;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.service.AgentDeliveryService;
import ru.agimate.controlapi.service.dto.ToolResult;
import ru.agimate.controlapi.service.tool.ToolCallLogService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Closes the two windows a non-replayable call can be lost in, without ever running the tool again.
 *
 * <p>A claimed attempt that outlived its deadline has an unknown outcome: the guarded UPDATE writes
 * {@link #UNKNOWN_OUTCOME_ERROR}, and only the CAS winner delivers it. A finished detached result
 * whose process died before the {@code tool_completed} trigger was sent is redelivered from the row
 * as stored; the {@code claimDelivery} inside the delivery funnel stays the second duplicate guard.
 *
 * <p>Delivery runs on virtual threads so a batch does not hold the shared scheduler; a failing row is
 * logged by id and does not abort the rest.
 */
@Slf4j
@Component
public class NonReplayableToolRecovery {

    /** An outcome nobody can know: the command may have run, and re-running it is not allowed. */
    public static final String UNKNOWN_OUTCOME_ERROR =
            "Execution was interrupted or its outcome is unknown; the external action may have happened";

    private static final int BATCH_SIZE = 100;

    private final ToolCallLogService toolCallLogService;
    private final AgentDeliveryService agentDeliveryService;
    private final Executor executor;

    @Autowired
    public NonReplayableToolRecovery(ToolCallLogService toolCallLogService,
                                     AgentDeliveryService agentDeliveryService) {
        this(toolCallLogService, agentDeliveryService, Executors.newVirtualThreadPerTaskExecutor());
    }

    NonReplayableToolRecovery(ToolCallLogService toolCallLogService,
                              AgentDeliveryService agentDeliveryService,
                              Executor executor) {
        this.toolCallLogService = toolCallLogService;
        this.agentDeliveryService = agentDeliveryService;
        this.executor = executor;
    }

    @Scheduled(fixedDelay = 30_000)
    public void recover() {
        List<ToolCallLog> rows = toolCallLogService.findRecoverableDispatches(LocalDateTime.now(), BATCH_SIZE);
        if (rows.isEmpty()) {
            return;
        }
        log.debug("Recovering {} non-replayable tool call(s)", rows.size());
        for (ToolCallLog row : rows) {
            try {
                executor.execute(() -> recoverOne(row));
            } catch (RejectedExecutionException e) {
                // The executor is closing; the row stays unfinished and the next start picks it up
                log.warn("Recovery for tool call {} was rejected during shutdown", row.getId());
            }
        }
    }

    private void recoverOne(ToolCallLog row) {
        try {
            if (row.getFinishAt() == null) {
                var unknown = new ToolResult(
                        row.getExternalId(), row.getConnectorCode(), null, UNKNOWN_OUTCOME_ERROR);
                if (toolCallLogService.finishNonReplayable(row.getId(), unknown)) {
                    agentDeliveryService.deliverToolResult(row, unknown);
                }
                return;
            }
            var result = new ToolResult(
                    row.getExternalId(), row.getConnectorCode(), row.getOutput(), row.getError());
            agentDeliveryService.deliverToolResult(row, result);
        } catch (Exception e) {
            log.warn("Failed to recover tool call {}", row.getId());
        }
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService service) {
            service.shutdown();
        }
    }
}
