package ru.agimate.controlapi.service.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Observability of runs' liveness. A live run constantly calls into control-api (SaveMessage,
 * ExecuteToolAsync/GetToolResult, GetRunContext) — every such RPC extends {@code last_activity_at};
 * a run silent for longer than {@link #STALE_AFTER} (the worker died without a SaveMessage(ERROR)) is
 * collected by the sweeper. It blocks nobody — single-writer is held by the partitioned queue, and
 * the status is only a projection for history and monitoring.
 *
 * <p>The threshold must exceed the longest legitimate quiet stretch of a run — one LLM call with all
 * its retries (at the worker: 4 attempts with backoff).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunActivityService {

    static final Duration STALE_AFTER = Duration.ofMinutes(15);
    static final String STALE_ERROR = "run went silent (no worker activity); swept as stale";

    private final AgentRunRepository agentRunRepository;

    /** The run's sign of life — best-effort: a failure to stamp it must not fail the RPC itself. */
    public void touch(UUID runId) {
        try {
            agentRunRepository.touchActivity(runId, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("touchActivity failed for run {}: {}", runId, e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void sweepStaleRunning() {
        LocalDateTime cutoff = LocalDateTime.now().minus(STALE_AFTER);
        int swept = agentRunRepository.failStaleRunning(cutoff, STALE_ERROR);
        if (swept > 0) {
            log.warn("swept {} stale RUNNING run(s) older than {}", swept, STALE_AFTER);
        }
        // A run asked to stop and then gone silent never reached a seam to see the request. It is
        // cancelled rather than failed: the user's intent explains the outcome, the silence does not.
        int cancelled = agentRunRepository.cancelStaleRequested(cutoff);
        if (cancelled > 0) {
            log.warn("swept {} stale run(s) with cancellation requested", cancelled);
        }
    }
}
