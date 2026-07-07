package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.entities.TriggerLogAgent;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface TriggerLogAgentRepository extends JpaRepository<TriggerLogAgent, UUID> {

    /**
     * RegisterRun: the worker acquires the session writer slot for an existing run row.
     * The claim is conditional in the statement itself ({@code NOT EXISTS} another RUNNING
     * holder, self excluded for the idempotent re-affirm) — a busy slot is the regular
     * {@code updated == 0} outcome, not a constraint violation. The partial unique index
     * stays as the invariant backstop for true races (two concurrent claims can both pass
     * {@code NOT EXISTS} under READ COMMITTED; the loser hits the index).
     * Guarded to non-terminal statuses: a late/replayed register on a DONE/FAILED/CANCELLED
     * row must not flip it back to RUNNING (that would re-occupy the session slot with a
     * run that has already finished and will never release it).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE TriggerLogAgent t
            SET t.status = ru.agimate.controlapi.database.enums.RunStatus.RUNNING,
                t.sessionId = :sessionId,
                t.expiresAt = :expiresAt,
                t.updatedAt = :acquiredAt
            WHERE t.id = :runId
              AND t.status IN (ru.agimate.controlapi.database.enums.RunStatus.ENQUEUED,
                               ru.agimate.controlapi.database.enums.RunStatus.RUNNING)
              AND NOT EXISTS (
                  SELECT 1 FROM TriggerLogAgent h
                  WHERE h.sessionId = :sessionId
                    AND h.status = ru.agimate.controlapi.database.enums.RunStatus.RUNNING
                    AND h.id <> :runId)
            """)
    int markRunning(@Param("runId") UUID runId,
                    @Param("sessionId") UUID sessionId,
                    @Param("expiresAt") LocalDateTime expiresAt,
                    @Param("acquiredAt") LocalDateTime acquiredAt);

    /**
     * The session slot's holder as the claim sees it: the RUNNING row regardless of
     * {@code expires_at} — the partial unique index (and thus {@code markRunning}) ignores
     * expiry, so an expired holder still blocks the claim until evicted.
     */
    Optional<TriggerLogAgent> findBySessionIdAndStatus(UUID sessionId, RunStatus status);

    /**
     * GetActiveRun: the single live writer for the session, if any.
     * Expired RUNNING rows are treated as inactive reads; the claim path evicts them
     * via {@code reclaimDeadHolder}.
     */
    @Query("""
            SELECT t FROM TriggerLogAgent t
            WHERE t.sessionId = :sessionId
              AND t.status = ru.agimate.controlapi.database.enums.RunStatus.RUNNING
              AND t.expiresAt > :now
            """)
    Optional<TriggerLogAgent> findActiveBySession(@Param("sessionId") UUID sessionId,
                                                  @Param("now") LocalDateTime now);

    /**
     * ReleaseRun: release-own. Only the run that currently holds the slot can release it,
     * so a late Release from a pre-empted (CANCELLED) run is a no-op.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE TriggerLogAgent t
            SET t.status = :terminalStatus
            WHERE t.id = :runId
              AND t.status = ru.agimate.controlapi.database.enums.RunStatus.RUNNING
            """)
    int releaseOwn(@Param("runId") UUID runId,
                   @Param("terminalStatus") RunStatus terminalStatus);
}
