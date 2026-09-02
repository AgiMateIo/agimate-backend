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
import java.util.Optional;
import java.util.UUID;

public interface ToolCallLogRepository extends JpaRepository<ToolCallLog, UUID> {

    Optional<ToolCallLog> findByExternalIdAndAgentId(String externalId, UUID agentId);

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
            AND (:since IS NULL OR t.createdAt >= :since)
            AND (:until IS NULL OR t.createdAt <= :until)
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
