package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
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
public interface ConnectorTaskRepository extends JpaRepository<ConnectorTask, UUID>, ConnectorTaskRepositoryCustom {

    @Query("""
            SELECT t FROM ConnectorTask t
            WHERE t.connectorCode = :connectorCode
              AND ((:identity IS NULL AND t.identity IS NULL) OR t.identity = :identity)
              AND t.taskName = :taskName
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

    /** Удаляет строки identity, чьи task_name больше не декларируются коннектором. */
    @Modifying
    @Query("""
            DELETE FROM ConnectorTask t
            WHERE t.connectorCode = :connectorCode
              AND ((:identity IS NULL AND t.identity IS NULL) OR t.identity = :identity)
              AND t.taskName NOT IN :keepTaskNames
            """)
    int deleteStale(
            @Param("connectorCode") String connectorCode,
            @Param("identity") String identity,
            @Param("keepTaskNames") Collection<String> keepTaskNames);

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

    /** Удаляет задачу с проверкой владельца (user + agent); {@code > 0} — действительно удалена. */
    @Modifying
    @Query("""
            DELETE FROM ConnectorTask t
            WHERE t.id = :id
              AND t.connectorCode = :connectorCode
              AND t.userId = :userId
              AND t.agentId = :agentId
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
}
