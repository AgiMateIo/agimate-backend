package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.entities.AgentRun;

import java.time.LocalDateTime;
import java.util.UUID;

public interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {

    // REQUIRES_NEW: the calls arrive both from bare gRPC threads (Hibernate rejects @Modifying with no TX) and
    // from the facades' readOnly transactions (AgentContextGrpcService) — a short writing TX of its own is
    // correct from either context.
    /** The run's sign of life: any of its RPCs extends the activity mark (only while RUNNING). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query("""
            UPDATE AgentRun t
            SET t.lastActivityAt = :now
            WHERE t.id = :runId
              AND t.status = ru.agimate.controlapi.database.enums.RunStatus.RUNNING
            """)
    int touchActivity(@Param("runId") UUID runId, @Param("now") LocalDateTime now);

    /**
     * Sweeper for stuck runs: RUNNING with no sign of life for longer than the threshold → FAILED
     * (the worker died silently, without a SaveMessage(ERROR)). Observability; it blocks nobody.
     *
     * <p>One already asked to stop goes to CANCELLED instead ({@link #cancelStaleRequested}) — hence
     * the {@code cancelRequestedAt IS NULL} here.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AgentRun t
            SET t.status = ru.agimate.controlapi.database.enums.RunStatus.FAILED,
                t.error = :error
            WHERE t.status = ru.agimate.controlapi.database.enums.RunStatus.RUNNING
              AND t.cancelRequestedAt IS NULL
              AND t.lastActivityAt < :cutoff
            """)
    int failStaleRunning(@Param("cutoff") LocalDateTime cutoff, @Param("error") String error);

    /** Asked to stop, then silent: the worker died before a seam, and the intent explains it better than silence. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AgentRun t
            SET t.status = ru.agimate.controlapi.database.enums.RunStatus.CANCELLED
            WHERE t.status = ru.agimate.controlapi.database.enums.RunStatus.RUNNING
              AND t.cancelRequestedAt IS NOT NULL
              AND t.lastActivityAt < :cutoff
            """)
    int cancelStaleRequested(@Param("cutoff") LocalDateTime cutoff);

    /** Only a live run is touched: a terminal one has already happened, which is what makes this idempotent. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AgentRun t
            SET t.cancelRequestedAt = :now
            WHERE t.id = :runId
              AND t.cancelRequestedAt IS NULL
              AND t.status IN (ru.agimate.controlapi.database.enums.RunStatus.ENQUEUED,
                               ru.agimate.controlapi.database.enums.RunStatus.RUNNING)
            """)
    int requestCancel(@Param("runId") UUID runId, @Param("now") LocalDateTime now);

    /** Every live run of a session, queued ones included — otherwise the next starts a second later. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AgentRun t
            SET t.cancelRequestedAt = :now
            WHERE t.sessionId = :sessionId
              AND t.cancelRequestedAt IS NULL
              AND t.status IN (ru.agimate.controlapi.database.enums.RunStatus.ENQUEUED,
                               ru.agimate.controlapi.database.enums.RunStatus.RUNNING)
            """)
    int requestCancelBySession(@Param("sessionId") UUID sessionId, @Param("now") LocalDateTime now);

    /** Read on every seam RPC, so it selects one column rather than the row. */
    @Query("SELECT t.cancelRequestedAt IS NOT NULL FROM AgentRun t WHERE t.id = :runId")
    Boolean isCancelRequested(@Param("runId") UUID runId);
}
