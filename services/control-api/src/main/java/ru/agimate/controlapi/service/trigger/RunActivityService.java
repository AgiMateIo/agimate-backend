package ru.agimate.controlapi.service.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Observability of runs' liveness. A live run constantly calls into control-api (SaveMessage,
 * ExecuteToolAsync/GetToolResult, GetRunContext) — every such RPC extends {@code last_activity_at};
 * a run silent for longer than {@link #STALE_AFTER} (the worker died without a SaveMessage(ERROR)) is
 * collected by the sweeper. It blocks nobody — single-writer is held by the partitioned queue, and
 * the status is only a projection for history and monitoring.
 *
 * <p>A swept run never reports anything itself, so the sweeper announces it ({@link RunsSwept}); the
 * status stays observability for everyone but a listener that is waiting for that run's outcome.
 *
 * <p>The threshold must exceed the longest legitimate quiet stretch of a run — one LLM call with all
 * its retries (at the worker: 4 attempts with backoff).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunActivityService {

    /** Public because it is also the horizon past which a listing stops calling a run live. */
    public static final Duration STALE_AFTER = Duration.ofMinutes(15);
    public static final String STALE_ERROR = "run went silent (no worker activity); swept as stale";

    private final AgentRunRepository agentRunRepository;
    private final ApplicationEventPublisher eventPublisher;

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
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minus(STALE_AFTER);
        List<UUID> swept = agentRunRepository.failStaleRunning(cutoff, STALE_ERROR, now);
        if (!swept.isEmpty()) {
            log.warn("swept {} stale RUNNING run(s) older than {}", swept.size(), STALE_AFTER);
            // After commit: a listener acting on the FAILED status must see it.
            eventPublisher.publishEvent(new RunsSwept(swept));
        }
        // Asked to stop, then gone silent: the user's intent explains the outcome better than the silence.
        int cancelled = agentRunRepository.cancelStaleRequested(cutoff);
        if (cancelled > 0) {
            log.warn("swept {} stale run(s) with cancellation requested", cancelled);
        }
    }
}
