package ru.agimate.controlapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.database.entities.ToolCallLog;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ToolCallLogRepository extends JpaRepository<ToolCallLog, UUID> {

    Optional<ToolCallLog> findByExternalIdAndAgentId(String externalId, UUID agentId);

    /** The journal rows behind a page's tool-call ids; {@code external_id} is unique per agent. */
    List<ToolCallLog> findByAgentIdAndExternalIdIn(UUID agentId, Collection<String> externalIds);

    /**
     * The detach stamp — the ownership flip of the call's result. Guarded on both facts: a call that
     * finished first must come back as a plain result (no trigger will fire for it), and a repeated
     * detach must not move the timestamp. 0 rows updated → re-read the row and look which guard held.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ToolCallLog t
            SET t.detachedAt = :now, t.updatedAt = :now
            WHERE t.agentId = :agentId
              AND t.externalId = :externalId
              AND t.finishAt IS NULL
              AND t.detachedAt IS NULL
            """)
    int markDetached(@Param("agentId") UUID agentId,
                     @Param("externalId") String externalId,
                     @Param("now") LocalDateTime now);

    /**
     * Claims the detached delivery, exactly once: the winner creates the delivery run in the same
     * transaction (a rollback releases the claim). Guards against a duplicate result post from an
     * app producing a second run — and a second answer to the user.
     *
     * <p>No {@code clearAutomatically} on purpose: this runs mid-transaction, after the caller has
     * loaded the parent run — clearing would detach it and snap its lazy {@code agent} proxy. The
     * claim's truth is the returned row count, nothing is re-read after it.
     */
    @Modifying
    @Query("""
            UPDATE ToolCallLog t
            SET t.deliveredAt = :now, t.updatedAt = :now
            WHERE t.id = :id
              AND t.deliveredAt IS NULL
            """)
    int claimDelivery(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /**
     * Cooperative cancel of an MCP task: the stamp lands only while the call still runs, so a
     * stamped row that later finishes reads as "cancelled". Cancelling a finished task is 0 rows
     * and the task stays completed — the spec allows a terminal status other than cancelled.
     */
    @Modifying
    @Query("""
            UPDATE ToolCallLog t
            SET t.cancelRequestedAt = :now, t.updatedAt = :now
            WHERE t.id = :id
              AND t.finishAt IS NULL
              AND t.cancelRequestedAt IS NULL
            """)
    int markCancelRequested(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /**
     * The single dispatch of a non-replayable call: the first executor stamps the row, every later
     * one gets 0 rows and must not run the tool. Guarded on {@code finish_at} too, so a call that is
     * already finalized is never claimed. No {@code clearAutomatically}: the caller may still hold
     * loaded entities from the surrounding transaction, and the returned row count is the truth.
     */
    @Modifying
    @Query("""
            UPDATE ToolCallLog t
            SET t.dispatchAt = :now, t.dispatchDeadlineAt = :deadline, t.updatedAt = :now
            WHERE t.id = :id
              AND t.dispatchAt IS NULL
              AND t.finishAt IS NULL
            """)
    int claimDispatch(@Param("id") UUID id,
                      @Param("now") LocalDateTime now,
                      @Param("deadline") LocalDateTime deadline);

    /** Best-effort check after the claim: the row was finalized in the window before the tool ran. */
    boolean existsByIdAndFinishAtIsNull(UUID id);

    /** Whether some attempt already claimed the row — a pre-claim failure must not write over it. */
    boolean existsByIdAndDispatchAtIsNotNull(UUID id);

    /**
     * The single finalizer of a non-replayable call, whatever the outcome: success, a safe error, a
     * cancellation after the claim, or the {@code unknown} recovery writes for a lost execution.
     * Guarded on {@code finish_at}: exactly one caller changes the row, and only that caller may
     * deliver the result — a late actual result must not overwrite an {@code unknown}, and a second
     * recovery tick must not finalize the row again. No {@code clearAutomatically}: the returned row
     * count is the truth, nothing is re-read after it.
     */
    @Modifying
    @Query("""
            UPDATE ToolCallLog t
            SET t.finishAt = :now, t.output = :output, t.error = :error, t.updatedAt = :now
            WHERE t.id = :id
              AND t.finishAt IS NULL
            """)
    int finishNonReplayable(@Param("id") UUID id,
                            @Param("now") LocalDateTime now,
                            @Param("output") String output,
                            @Param("error") String error);

    /**
     * The two windows recovery closes: a claimed attempt that outlived its deadline (its executor is
     * gone, so the outcome is unknown), and a finished detached result whose process died before the
     * {@code tool_completed} trigger was delivered. The second branch is filtered on a live,
     * push-capable agent and a live originating run: MCP tasks have no {@code run_id}, and a
     * cancelled parent suppresses delivery on purpose, so neither ever gets {@code delivered_at} —
     * without the filter they would be selected every tick forever.
     *
     * <p>{@code MCP} is the only transport whose {@code supportsPush()} is false; a future non-push
     * type must be added here, because the funnel intentionally never stamps {@code delivered_at}
     * for a withheld result. The explicit {@code deletedAt IS NULL} repeats the entity's
     * {@code @SQLRestriction} so the intent survives a removal of that annotation.
     */
    @Query("""
            SELECT t FROM ToolCallLog t
            WHERE t.dispatchAt IS NOT NULL
              AND (
                (t.finishAt IS NULL AND t.dispatchDeadlineAt <= :now)
                OR
                (t.finishAt IS NOT NULL
                 AND t.detachedAt IS NOT NULL
                 AND t.deliveredAt IS NULL
                 AND t.runId IS NOT NULL
                 AND EXISTS (SELECT 1 FROM AgentRun r
                             WHERE r.id = t.runId AND r.cancelRequestedAt IS NULL)
                 AND EXISTS (SELECT 1 FROM Agent a
                             WHERE a.id = t.agentId
                               AND a.deletedAt IS NULL
                               AND a.type <> ru.agimate.controlapi.database.enums.AgentType.MCP))
              )
            ORDER BY t.dispatchDeadlineAt
            """)
    List<ToolCallLog> findRecoverableDispatches(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * Live MCP tasks of the agent: detached, unfinished, younger than {@code cutoff} — an expired
     * orphan (a restart killed the execution mid-flight) must not eat the cap forever.
     */
    @Query("""
            SELECT COUNT(t) FROM ToolCallLog t
            WHERE t.agentId = :agentId
              AND t.detachedAt IS NOT NULL
              AND t.finishAt IS NULL
              AND t.createdAt > :cutoff
            """)
    long countLiveDetached(@Param("agentId") UUID agentId, @Param("cutoff") LocalDateTime cutoff);

    /**
     * Calls of one tool by the user that went through: permitted and not failed. The web connector's
     * daily search cap reads this — a denied or failed call cost the platform nothing. A call still
     * running counts, the caller's own included.
     */
    @Query("""
            SELECT COUNT(t) FROM ToolCallLog t
            WHERE t.userId = :userId
              AND t.connectorCode = :connectorCode
              AND t.name = :name
              AND t.accessEffect = ru.agimate.controlapi.abac.AccessEffect.ALLOW
              AND t.error IS NULL
              AND t.createdAt > :since
            """)
    long countSucceeded(@Param("userId") UUID userId,
                        @Param("connectorCode") String connectorCode,
                        @Param("name") String name,
                        @Param("since") LocalDateTime since);

    // CAST on the since/until null checks: pgjdbc sends a timestamp bind with an unspecified type
    // oid, and Postgres cannot infer one from a bare "? IS NULL" (42P18).
    /**
     * {@code status} is a string {@link ru.agimate.controlapi.controller.manage.dto.ToolCallStatus}
     * ({@code SUCCESS}/{@code ERROR}/{@code PENDING}), derived from {@code finish_at}/{@code error}.
     * {@code name} is a case-insensitive substring search over the tool's name.
     */
    @Query("""
            SELECT t FROM ToolCallLog t
            WHERE t.userId = :userId
            AND (:agentId IS NULL OR t.agentId = :agentId)
            AND (:connectorCode IS NULL OR t.connectorCode = :connectorCode)
            AND (:connectionId IS NULL OR t.connectionId = :connectionId)
            AND (:accessEffect IS NULL OR t.accessEffect = :accessEffect)
            AND (:name IS NULL OR LOWER(t.name) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%')))
            AND (:status IS NULL
                 OR (:status = 'SUCCESS' AND t.finishAt IS NOT NULL AND t.error IS NULL)
                 OR (:status = 'ERROR'   AND t.error IS NOT NULL)
                 OR (:status = 'PENDING' AND t.finishAt IS NULL AND t.error IS NULL))
            AND (CAST(:since AS LocalDateTime) IS NULL OR t.createdAt >= :since)
            AND (CAST(:until AS LocalDateTime) IS NULL OR t.createdAt <= :until)
            ORDER BY t.createdAt DESC
            """)
    Page<ToolCallLog> findWithFilters(@Param("userId") UUID userId,
                                      @Param("agentId") UUID agentId,
                                      @Param("connectorCode") String connectorCode,
                                      @Param("connectionId") String connectionId,
                                      @Param("accessEffect") AccessEffect accessEffect,
                                      @Param("name") String name,
                                      @Param("status") String status,
                                      @Param("since") LocalDateTime since,
                                      @Param("until") LocalDateTime until,
                                      Pageable pageable);
}
