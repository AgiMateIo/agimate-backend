package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.ConnectorJob;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectorJobRepository extends JpaRepository<ConnectorJob, UUID>,
        JpaSpecificationExecutor<ConnectorJob>, ConnectorJobRepositoryCustom {

    /** Поиск по бизнес-ключу — осмыслен только для SYSTEM-строк (reconcile-синк, ≤1 строка на ключ). */
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
     * Удаляет SYSTEM-строки connection_id, чьи name больше не декларируются коннектором.
     * Динамические задачи (USER/AGENT) пересинк деклараций не трогает.
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

    /** Удаляет все SYSTEM-строки connection_id — когда коннектор больше не декларирует ни одной задачи. */
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

    /** Активные (не COMPLETED) динамические задачи агента — для list. */
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
     * Удаляет задачу с проверкой владельца (user + agent); {@code > 0} — действительно удалена.
     * Только {@code kind=AGENT}: тулой агент отменяет лишь созданное им самим — USER-задачу,
     * адресованную агенту (тот же {@code agent_id}), отменяет только пользователь через manage-API.
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

    /**
     * Shutdown-release: возвращает RUNNING-строку в PENDING с немедленным {@code next_run_at};
     * {@code last_error} не трогаем. Guard по статусу — ONETIME, успевший финализироваться
     * ({@code markCompleted}) в гонке с остановкой, не воскрешаем.
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

    /** Снимает все задачи агента (динамические + адресованные ему) — вызывается при удалении агента. */
    @Modifying
    @Query("DELETE FROM ConnectorJob t WHERE t.agentId = :agentId")
    int deleteByAgentId(@Param("agentId") UUID agentId);

    /**
     * Пауза: точечный UPDATE только {@code paused_at} — entity-save затёр бы {@code status}/
     * {@code lease_until}, которые конкурентно пишет scheduler. 0 строк = уже на паузе (идемпотентно).
     */
    @Modifying
    @Query("""
            UPDATE ConnectorJob t
            SET t.pausedAt = :now
            WHERE t.id = :id AND t.userId = :userId AND t.pausedAt IS NULL
            """)
    int pause(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /** Возобновление с пересчитанным {@code next_run_at}; 0 строк = не была на паузе (идемпотентно). */
    @Modifying
    @Query("""
            UPDATE ConnectorJob t
            SET t.pausedAt = NULL, t.nextRunAt = :nextRunAt
            WHERE t.id = :id AND t.userId = :userId AND t.pausedAt IS NOT NULL
            """)
    int resume(@Param("id") UUID id, @Param("userId") UUID userId, @Param("nextRunAt") LocalDateTime nextRunAt);

    /**
     * «Запустить сейчас»: точечный UPDATE {@code next_run_at = now}, чтобы scheduler подхватил строку
     * на ближайшем тике. Только {@code PENDING} и не на паузе — {@code RUNNING}/{@code COMPLETED}/
     * приостановленные не трогаем. {@code PENDING} ⟹ {@code lease_until = NULL}, проверять lease не нужно.
     * 0 строк = строка уже не в этом состоянии (например, scheduler успел её claim'нуть).
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
