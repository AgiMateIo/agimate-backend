package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.ConnectorTask;

import java.time.LocalDateTime;
import java.util.Collection;
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
