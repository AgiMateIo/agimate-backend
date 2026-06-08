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
     * Flipping to RUNNING trips the partial unique index if another run already holds
     * the session — that is the single-writer guard (DataIntegrityViolationException).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE TriggerLogAgent t
            SET t.status = ru.agimate.controlapi.database.enums.RunStatus.RUNNING,
                t.sessionId = :sessionId,
                t.expiresAt = :expiresAt,
                t.updatedAt = :acquiredAt
            WHERE t.id = :runId
            """)
    int markRunning(@Param("runId") UUID runId,
                    @Param("sessionId") UUID sessionId,
                    @Param("expiresAt") LocalDateTime expiresAt,
                    @Param("acquiredAt") LocalDateTime acquiredAt);

    /**
     * GetActiveRun: the single live writer for the session, if any.
     * Expired RUNNING rows are treated as inactive (no sweeper needed).
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
