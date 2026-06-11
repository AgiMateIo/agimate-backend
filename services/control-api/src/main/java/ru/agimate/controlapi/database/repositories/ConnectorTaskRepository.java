package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.ConnectorTask;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectorTaskRepository extends JpaRepository<ConnectorTask, UUID>,
        JpaSpecificationExecutor<ConnectorTask>, ConnectorTaskRepositoryCustom {

    /** Поиск по бизнес-ключу — осмыслен только для SYSTEM-строк (reconcile-синк, ≤1 строка на ключ). */
    @Query("""
            SELECT t FROM ConnectorTask t
            WHERE t.connectorCode = :connectorCode
              AND ((:identity IS NULL AND t.identity IS NULL) OR t.identity = :identity)
              AND t.taskName = :taskName
              AND t.kind = ru.agimate.controlapi.database.enums.ConnectorTaskKind.SYSTEM
            """)
    Optional<ConnectorTask> findByBusinessKey(
            @Param("connectorCode") String connectorCode,
            @Param("identity") String identity,
            @Param("taskName") String taskName);

    @Modifying
    @Query("""
            DELETE FROM ConnectorTask t
            WHERE t.connectorCode = :connectorCode
              AND ((:identity IS NULL AND t.identity IS NULL) OR t.identity = :identity)
            """)
    int deleteByIdentity(
            @Param("connectorCode") String connectorCode,
            @Param("identity") String identity);

    /**
     * Удаляет SYSTEM-строки identity, чьи task_name больше не декларируются коннектором.
     * Динамические задачи (USER/AGENT) пересинк деклараций не трогает.
     */
    @Modifying
    @Query("""
            DELETE FROM ConnectorTask t
            WHERE t.connectorCode = :connectorCode
              AND ((:identity IS NULL AND t.identity IS NULL) OR t.identity = :identity)
              AND t.kind = ru.agimate.controlapi.database.enums.ConnectorTaskKind.SYSTEM
              AND t.taskName NOT IN :keepTaskNames
            """)
    int deleteStale(
            @Param("connectorCode") String connectorCode,
            @Param("identity") String identity,
            @Param("keepTaskNames") Collection<String> keepTaskNames);

    /** Удаляет все SYSTEM-строки identity — когда коннектор больше не декларирует ни одной задачи. */
    @Modifying
    @Query("""
            DELETE FROM ConnectorTask t
            WHERE t.connectorCode = :connectorCode
              AND ((:identity IS NULL AND t.identity IS NULL) OR t.identity = :identity)
              AND t.kind = ru.agimate.controlapi.database.enums.ConnectorTaskKind.SYSTEM
            """)
    int deleteSystemByIdentity(
            @Param("connectorCode") String connectorCode,
            @Param("identity") String identity);

    /** Активные (не COMPLETED) динамические задачи агента — для list. */
    @Query("""
            SELECT t FROM ConnectorTask t
            WHERE t.connectorCode = :connectorCode
              AND t.userId = :userId
              AND t.agentId = :agentId
              AND t.status <> ru.agimate.controlapi.database.enums.ConnectorTaskStatus.COMPLETED
            ORDER BY t.nextRunAt NULLS FIRST
            """)
    List<ConnectorTask> findActiveByAgent(
            @Param("connectorCode") String connectorCode,
            @Param("userId") UUID userId,
            @Param("agentId") UUID agentId);

    /**
     * Удаляет задачу с проверкой владельца (user + agent); {@code > 0} — действительно удалена.
     * Только {@code kind=AGENT}: тулой агент отменяет лишь созданное им самим — USER-задачу,
     * адресованную агенту (тот же {@code agent_id}), отменяет только пользователь через manage-API.
     */
    @Modifying
    @Query("""
            DELETE FROM ConnectorTask t
            WHERE t.id = :id
              AND t.connectorCode = :connectorCode
              AND t.userId = :userId
              AND t.agentId = :agentId
              AND t.kind = ru.agimate.controlapi.database.enums.ConnectorTaskKind.AGENT
            """)
    int deleteOwned(
            @Param("id") UUID id,
            @Param("connectorCode") String connectorCode,
            @Param("userId") UUID userId,
            @Param("agentId") UUID agentId);

    @Modifying
    @Query("""
            UPDATE ConnectorTask t
            SET t.status = ru.agimate.controlapi.database.enums.ConnectorTaskStatus.PENDING,
                t.leaseUntil = NULL,
                t.nextRunAt = :nextRunAt,
                t.lastError = :lastError
            WHERE t.id = :id
            """)
    int complete(@Param("id") UUID id,
                 @Param("nextRunAt") LocalDateTime nextRunAt,
                 @Param("lastError") String lastError);

    @Modifying
    @Query("""
            UPDATE ConnectorTask t
            SET t.status = ru.agimate.controlapi.database.enums.ConnectorTaskStatus.COMPLETED,
                t.leaseUntil = NULL,
                t.nextRunAt = NULL,
                t.lastError = :lastError
            WHERE t.id = :id
            """)
    int markCompleted(@Param("id") UUID id,
                      @Param("lastError") String lastError);

    /** Снимает все задачи агента (динамические + адресованные ему) — вызывается при удалении агента. */
    @Modifying
    @Query("DELETE FROM ConnectorTask t WHERE t.agentId = :agentId")
    int deleteByAgentId(@Param("agentId") UUID agentId);

    /**
     * Пауза: точечный UPDATE только {@code paused_at} — entity-save затёр бы {@code status}/
     * {@code lease_until}, которые конкурентно пишет scheduler. 0 строк = уже на паузе (идемпотентно).
     */
    @Modifying
    @Query("""
            UPDATE ConnectorTask t
            SET t.pausedAt = :now
            WHERE t.id = :id AND t.userId = :userId AND t.pausedAt IS NULL
            """)
    int pause(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /** Возобновление с пересчитанным {@code next_run_at}; 0 строк = не была на паузе (идемпотентно). */
    @Modifying
    @Query("""
            UPDATE ConnectorTask t
            SET t.pausedAt = NULL, t.nextRunAt = :nextRunAt
            WHERE t.id = :id AND t.userId = :userId AND t.pausedAt IS NOT NULL
            """)
    int resume(@Param("id") UUID id, @Param("userId") UUID userId, @Param("nextRunAt") LocalDateTime nextRunAt);
}
