package ru.agimate.controlapi.service.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.entities.TriggerLogAgent;
import ru.agimate.controlapi.database.repositories.TriggerLogAgentRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Active-run registry (AgentRunRegistry) over {@link TriggerLogAgent}.
 * A run row is created by the backend at trigger routing (status ENQUEUED);
 * the worker transitions it RUNNING on start (register) and DONE on finish (release).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentRunRegistryService {

    /** Default TTL backstop on a dead run; covers worst-case run duration. */
    static final int DEFAULT_TTL_SECONDS = 3600;

    private final TriggerLogAgentRepository triggerLogAgentRepository;

    /** Immutable view of the active run, materialized inside the transaction. */
    public record ActiveRunView(
            UUID agentId,
            UUID sessionId,
            UUID runId,
            LocalDateTime acquiredAt,
            LocalDateTime expiresAt
    ) {}

    @Transactional
    public ActiveRunView registerRun(UUID sessionId, UUID runId, int ttlSeconds) {
        int ttl = ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS;
        LocalDateTime acquiredAt = LocalDateTime.now();
        LocalDateTime expiresAt = acquiredAt.plusSeconds(ttl);

        int updated = triggerLogAgentRepository.markRunning(runId, sessionId, expiresAt, acquiredAt);
        if (updated == 0) {
            throw new NotFoundStatusException("Run not found: " + runId);
        }

        TriggerLogAgent run = triggerLogAgentRepository.findById(runId)
                .orElseThrow(() -> new NotFoundStatusException("Run not found: " + runId));
        log.debug("RegisterRun session={} run={} agent={} expiresAt={}",
                sessionId, runId, run.getAgent().getId(), expiresAt);
        return toView(run);
    }

    public Optional<ActiveRunView> getActiveRun(UUID sessionId) {
        return triggerLogAgentRepository
                .findActiveBySession(sessionId, LocalDateTime.now())
                .map(this::toView);
    }

    @Transactional
    public boolean releaseRun(UUID runId) {
        boolean released = triggerLogAgentRepository.releaseOwn(runId, RunStatus.DONE) == 1;
        log.debug("ReleaseRun run={} released={}", runId, released);
        return released;
    }

    private ActiveRunView toView(TriggerLogAgent run) {
        return new ActiveRunView(
                run.getAgent().getId(),
                run.getSessionId(),
                run.getId(),
                run.getUpdatedAt(),
                run.getExpiresAt()
        );
    }
}
