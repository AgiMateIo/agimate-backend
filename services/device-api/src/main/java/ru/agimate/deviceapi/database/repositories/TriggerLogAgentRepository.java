package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.deviceapi.database.enums.RunStatus;
import ru.agimate.deviceapi.database.entities.TriggerLogAgent;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface TriggerLogAgentRepository extends JpaRepository<TriggerLogAgent, Long> {

    Optional<TriggerLogAgent> findByPubId(UUID pubId);

    /**
     * RegisterRun: the worker acquires the session writer slot for an existing run row.
     * Flipping to RUNNING trips the partial unique index if another run already holds
     * the session — that is the single-writer guard (DataIntegrityViolationException).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE TriggerLogAgent t
            SET t.status = ru.agimate.deviceapi.database.enums.RunStatus.RUNNING,
                t.sessionPubId = :sessionPubId,
                t.expiresAt = :expiresAt,
                t.updatedAt = :acquiredAt
            WHERE t.pubId = :runId
            """)
    int markRunning(@Param("runId") UUID runId,
                    @Param("sessionPubId") UUID sessionPubId,
                    @Param("expiresAt") LocalDateTime expiresAt,
                    @Param("acquiredAt") LocalDateTime acquiredAt);

    /**
     * GetActiveRun: the single live writer for the session, if any.
     * Expired RUNNING rows are treated as inactive (no sweeper needed).
     */
    @Query("""
            SELECT t FROM TriggerLogAgent t
            WHERE t.sessionPubId = :sessionPubId
              AND t.status = ru.agimate.deviceapi.database.enums.RunStatus.RUNNING
              AND t.expiresAt > :now
            """)
    Optional<TriggerLogAgent> findActiveBySession(@Param("sessionPubId") UUID sessionPubId,
                                                  @Param("now") LocalDateTime now);

    /**
     * ReleaseRun: release-own. Only the run that currently holds the slot can release it,
     * so a late Release from a pre-empted (CANCELLED) run is a no-op.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE TriggerLogAgent t
            SET t.status = :terminalStatus
            WHERE t.pubId = :runId
              AND t.status = ru.agimate.deviceapi.database.enums.RunStatus.RUNNING
            """)
    int releaseOwn(@Param("runId") UUID runId,
                   @Param("terminalStatus") RunStatus terminalStatus);
}
