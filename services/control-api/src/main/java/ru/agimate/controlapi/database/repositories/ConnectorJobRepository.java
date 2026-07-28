package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.ConnectorJob;
import ru.agimate.controlapi.database.enums.ConnectorJobKind;
import ru.agimate.controlapi.database.enums.ConnectorJobType;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectorJobRepository extends JpaRepository<ConnectorJob, UUID>,
        JpaSpecificationExecutor<ConnectorJob>, ConnectorJobRepositoryCustom {

    /** Lookup by business key — meaningful for SYSTEM rows only (the reconcile sync, ≤1 row per key). */
    @Query("""
            SELECT t FROM ConnectorJob t
            WHERE t.connectorCode = :connectorCode
              AND ((:connectionId IS NULL AND t.connectionId IS NULL) OR t.connectionId = :connectionId)
              AND t.name = :name
              AND t.kind = ru.agimate.controlapi.database.enums.ConnectorJobKind.SYSTEM
            """)
    Optional<ConnectorJob> findByBusinessKey(
            @Param("connectorCode") String connectorCode,
            @Param("connectionId") String connectionId,
            @Param("name") String name);

    @Modifying
    @Query("""
            DELETE FROM ConnectorJob t
            WHERE t.connectorCode = :connectorCode
              AND ((:connectionId IS NULL AND t.connectionId IS NULL) OR t.connectionId = :connectionId)
            """)
    int deleteByConnectionId(
            @Param("connectorCode") String connectorCode,
            @Param("connectionId") String connectionId);

    /**
     * Deletes SYSTEM rows of a connection_id whose names the connector no longer declares. A
     * re-sync of declarations leaves dynamic jobs (USER/AGENT) untouched.
     */
    @Modifying
    @Query("""
            DELETE FROM ConnectorJob t
            WHERE t.connectorCode = :connectorCode
              AND ((:connectionId IS NULL AND t.connectionId IS NULL) OR t.connectionId = :connectionId)
              AND t.kind = ru.agimate.controlapi.database.enums.ConnectorJobKind.SYSTEM
              AND t.name NOT IN :keepNames
            """)
    int deleteStale(
            @Param("connectorCode") String connectorCode,
            @Param("connectionId") String connectionId,
            @Param("keepNames") Collection<String> keepNames);

    /** Deletes every SYSTEM row of a connection_id — for when the connector declares no jobs at all. */
    @Modifying
    @Query("""
            DELETE FROM ConnectorJob t
            WHERE t.connectorCode = :connectorCode
              AND ((:connectionId IS NULL AND t.connectionId IS NULL) OR t.connectionId = :connectionId)
              AND t.kind = ru.agimate.controlapi.database.enums.ConnectorJobKind.SYSTEM
            """)
    int deleteSystemByConnectionId(
            @Param("connectorCode") String connectorCode,
            @Param("connectionId") String connectionId);

    /** An agent's active (non-COMPLETED) dynamic jobs — for list. */
    @Query("""
            SELECT t FROM ConnectorJob t
            WHERE t.connectorCode = :connectorCode
              AND t.userId = :userId
              AND t.agentId = :agentId
              AND t.status <> ru.agimate.controlapi.database.enums.ConnectorJobStatus.COMPLETED
            ORDER BY t.nextRunAt NULLS FIRST
            """)
    List<ConnectorJob> findActiveByAgent(
            @Param("connectorCode") String connectorCode,
            @Param("userId") UUID userId,
            @Param("agentId") UUID agentId);

    /**
     * Deletes a job with an owner check (user + agent); {@code > 0} means it really was deleted.
     * {@code kind=AGENT} only: through the tool an agent cancels only what it created itself — a
     * USER job addressed to an agent (same {@code agent_id}) can be cancelled by the user alone,
     * through the manage API.
     */
    @Modifying
    @Query("""
            DELETE FROM ConnectorJob t
            WHERE t.id = :id
              AND t.connectorCode = :connectorCode
              AND t.userId = :userId
              AND t.agentId = :agentId
              AND t.kind = ru.agimate.controlapi.database.enums.ConnectorJobKind.AGENT
            """)
    int deleteOwned(
            @Param("id") UUID id,
            @Param("connectorCode") String connectorCode,
            @Param("userId") UUID userId,
            @Param("agentId") UUID agentId);

    @Modifying
    @Query("""
            UPDATE ConnectorJob t
            SET t.status = ru.agimate.controlapi.database.enums.ConnectorJobStatus.PENDING,
                t.leaseUntil = NULL,
                t.nextRunAt = :nextRunAt,
                t.lastError = :lastError
            WHERE t.id = :id
            """)
    int complete(@Param("id") UUID id,
                 @Param("nextRunAt") LocalDateTime nextRunAt,
                 @Param("lastError") String lastError);

    /** Every row of a given kind — for the startup re-sync of SYSTEM jobs against connector declarations. */
    List<ConnectorJob> findByKind(ConnectorJobKind kind);

    /**
     * Updates the job's spec only (type/config/args/timeout): a targeted UPDATE — an entity save
     * would clobber {@code status}/{@code lease_until}, which the scheduler writes concurrently.
     */
    @Modifying
    @Query("""
            UPDATE ConnectorJob t
            SET t.type = :type,
                t.config = :config,
                t.args = :args,
                t.timeoutSeconds = :timeoutSeconds
            WHERE t.id = :id
            """)
    int updateSpec(@Param("id") UUID id,
                   @Param("type") ConnectorJobType type,
                   @Param("config") Map<String, Object> config,
                   @Param("args") Map<String, Object> args,
                   @Param("timeoutSeconds") Integer timeoutSeconds);

    /**
     * Shutdown release: returns a RUNNING row to PENDING with an immediate {@code next_run_at};
     * {@code last_error} is left alone. The status guard means a ONETIME that managed to finalise
     * ({@code markCompleted}) while racing the shutdown is not resurrected.
     */
    @Modifying
    @Query("""
            UPDATE ConnectorJob t
            SET t.status = ru.agimate.controlapi.database.enums.ConnectorJobStatus.PENDING,
                t.leaseUntil = NULL,
                t.nextRunAt = :nextRunAt
            WHERE t.id = :id
              AND t.status = ru.agimate.controlapi.database.enums.ConnectorJobStatus.RUNNING
            """)
    int release(@Param("id") UUID id, @Param("nextRunAt") LocalDateTime nextRunAt);

    @Modifying
    @Query("""
            UPDATE ConnectorJob t
            SET t.status = ru.agimate.controlapi.database.enums.ConnectorJobStatus.COMPLETED,
                t.leaseUntil = NULL,
                t.nextRunAt = NULL,
                t.lastError = :lastError
            WHERE t.id = :id
            """)
    int markCompleted(@Param("id") UUID id,
                      @Param("lastError") String lastError);

    /** Removes every job of an agent (dynamic ones plus those addressed to it) — called when an agent is deleted. */
    @Modifying
    @Query("DELETE FROM ConnectorJob t WHERE t.agentId = :agentId")
    int deleteByAgentId(@Param("agentId") UUID agentId);

    /**
     * Pause: a targeted UPDATE of {@code paused_at} only — an entity save would clobber
     * {@code status}/{@code lease_until}, which the scheduler writes concurrently. 0 rows = already
     * paused (idempotent).
     */
    @Modifying
    @Query("""
            UPDATE ConnectorJob t
            SET t.pausedAt = :now
            WHERE t.id = :id AND t.userId = :userId AND t.pausedAt IS NULL
            """)
    int pause(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /** Resume with a recomputed {@code next_run_at}; 0 rows = it was not paused (idempotent). */
    @Modifying
    @Query("""
            UPDATE ConnectorJob t
            SET t.pausedAt = NULL, t.nextRunAt = :nextRunAt
            WHERE t.id = :id AND t.userId = :userId AND t.pausedAt IS NOT NULL
            """)
    int resume(@Param("id") UUID id, @Param("userId") UUID userId, @Param("nextRunAt") LocalDateTime nextRunAt);

    /**
     * «Run now»: a targeted UPDATE of {@code next_run_at = now} so the scheduler picks the row up on
     * its next tick. {@code PENDING} and not paused only — {@code RUNNING}/{@code COMPLETED}/paused
     * rows are left alone. {@code PENDING} ⟹ {@code lease_until = NULL}, so the lease needs no check.
     * 0 rows = the row is no longer in that state (the scheduler claimed it first, for instance).
     */
    @Modifying
    @Query("""
            UPDATE ConnectorJob t
            SET t.nextRunAt = :now
            WHERE t.id = :id AND t.userId = :userId
              AND t.status = ru.agimate.controlapi.database.enums.ConnectorJobStatus.PENDING
              AND t.pausedAt IS NULL
            """)
    int runNow(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") LocalDateTime now);
}
