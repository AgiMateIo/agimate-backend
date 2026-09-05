package ru.agimate.agentworker.config;

import dev.dbos.transact.workflow.ListWorkflowsInput;
import dev.dbos.transact.workflow.WorkflowState;
import dev.dbos.transact.workflow.WorkflowStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Purge of finished workflows older than {@code agent.dbos.retention} via the library's public
 * admin API (its internal bulk GC is reachable only through the unauthenticated deprecated
 * admin HTTP server). Deletes only terminal workflows; checkpoints follow via cascade. Safe to
 * run concurrently across the fleet — deletion is idempotent.
 */
@Slf4j
@Component
public class DbosRetentionJob {

    static final int BATCH_LIMIT = 5000;
    private static final long INITIAL_DELAY_MS = 10 * 60 * 1000;
    private static final long FIXED_DELAY_MS = 6 * 60 * 60 * 1000;

    static final List<WorkflowState> TERMINAL_STATES = Arrays.stream(WorkflowState.values())
            .filter(s -> !s.isActive())
            .toList();

    private final DbosRuntime runtime;
    private final AgentProperties.Dbos dbosProps;

    public DbosRetentionJob(DbosRuntime runtime, AgentProperties props) {
        this.runtime = runtime;
        this.dbosProps = props.getDbos();
    }

    @Scheduled(initialDelay = INITIAL_DELAY_MS, fixedDelay = FIXED_DELAY_MS)
    public void purgeExpired() {
        Duration retention = dbosProps.getRetention();
        if (retention == null || retention.isZero() || retention.isNegative()) {
            return;
        }
        if (!dbosRunning()) {
            return;
        }
        Instant cutoff = Instant.now().minus(retention);
        try {
            int deleted = purgeOlderThan(cutoff);
            if (deleted > 0) {
                log.info("DBOS retention: deleted {} finished workflows older than {}", deleted, cutoff);
            } else {
                log.debug("DBOS retention: nothing to delete older than {}", cutoff);
            }
        } catch (Exception e) {
            log.warn("DBOS retention purge failed: {}", e.getMessage());
        }
    }

    boolean dbosRunning() {
        return runtime.isRunning();
    }

    int purgeOlderThan(Instant cutoff) {
        ListWorkflowsInput expired = new ListWorkflowsInput()
                .withStatus(TERMINAL_STATES)
                .withEndTime(cutoff)
                .withLimit(BATCH_LIMIT)
                .withLoadInput(false)
                .withLoadOutput(false);
        int total = 0;
        while (true) {
            List<String> batch = runtime.dbos().listWorkflows(expired).stream()
                    .map(WorkflowStatus::workflowId)
                    .toList();
            if (batch.isEmpty()) {
                return total;
            }
            runtime.dbos().deleteWorkflows(batch);
            total += batch.size();
            if (batch.size() < BATCH_LIMIT) {
                return total;
            }
        }
    }
}
