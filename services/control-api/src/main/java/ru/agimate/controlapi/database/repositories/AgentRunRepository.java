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
     * <p>A run whose cancellation was already requested is swept as CANCELLED instead
     * ({@link #cancelStaleRequested}) — hence the {@code cancelRequestedAt IS NULL} here.
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

    /**
     * The same sweep for a run that was asked to stop and then went silent: its worker died before it
     * could reach a seam. The user's intent explains the outcome better than «went silent», so the run
     * lands in CANCELLED rather than FAILED.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AgentRun t
            SET t.status = ru.agimate.controlapi.database.enums.RunStatus.CANCELLED
            WHERE t.status = ru.agimate.controlapi.database.enums.RunStatus.RUNNING
              AND t.cancelRequestedAt IS NOT NULL
              AND t.lastActivityAt < :cutoff
            """)
    int cancelStaleRequested(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Marks the run as asked to stop; the terminal status arrives later, when the run reaches a seam.
     * Only a live run is touched — a terminal one has already happened and cancelling it is a no-op,
     * which is what makes the operation idempotent.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AgentRun t
            SET t.cancelRequestedAt = :now,
                t.cancelledBy = :userId
            WHERE t.id = :runId
              AND t.cancelRequestedAt IS NULL
              AND t.status IN (ru.agimate.controlapi.database.enums.RunStatus.ENQUEUED,
                               ru.agimate.controlapi.database.enums.RunStatus.RUNNING)
            """)
    int requestCancel(@Param("runId") UUID runId, @Param("userId") UUID userId,
                      @Param("now") LocalDateTime now);

    /**
     * The same for every live run of a session — including those still queued behind the running one.
     * Without it the user stops one run and the next one in the partition starts a second later.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AgentRun t
            SET t.cancelRequestedAt = :now,
                t.cancelledBy = :userId
            WHERE t.sessionId = :sessionId
              AND t.cancelRequestedAt IS NULL
              AND t.status IN (ru.agimate.controlapi.database.enums.RunStatus.ENQUEUED,
                               ru.agimate.controlapi.database.enums.RunStatus.RUNNING)
            """)
    int requestCancelBySession(@Param("sessionId") UUID sessionId, @Param("userId") UUID userId,
                               @Param("now") LocalDateTime now);

    /** Is the run's cancellation requested? Read on the hot path (every seam RPC), so it selects one column. */
    @Query("SELECT t.cancelRequestedAt IS NOT NULL FROM AgentRun t WHERE t.id = :runId")
    Boolean isCancelRequested(@Param("runId") UUID runId);
}
