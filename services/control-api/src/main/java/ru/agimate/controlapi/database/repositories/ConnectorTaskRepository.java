package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.ConnectorTask;
import ru.agimate.controlapi.database.enums.ConnectorTaskScopeKind;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectorTaskRepository extends JpaRepository<ConnectorTask, UUID> {

    @Query("SELECT t FROM ConnectorTask t WHERE t.enabled = true")
    List<ConnectorTask> findAllEnabled();

    @Query("""
            SELECT t FROM ConnectorTask t
            WHERE t.connectorCode = :connectorCode
              AND t.scopeKind = :scopeKind
              AND ((:scopeId IS NULL AND t.scopeId IS NULL) OR t.scopeId = :scopeId)
              AND t.taskCode = :taskCode
            """)
    Optional<ConnectorTask> findByBusinessKey(
            @Param("connectorCode") String connectorCode,
            @Param("scopeKind") ConnectorTaskScopeKind scopeKind,
            @Param("scopeId") UUID scopeId,
            @Param("taskCode") String taskCode);

    @Modifying
    @Query("""
            DELETE FROM ConnectorTask t
            WHERE t.connectorCode = :connectorCode
              AND t.scopeKind = :scopeKind
              AND t.scopeId = :scopeId
            """)
    int deleteByScope(
            @Param("connectorCode") String connectorCode,
            @Param("scopeKind") ConnectorTaskScopeKind scopeKind,
            @Param("scopeId") UUID scopeId);

    @Modifying
    @Query("UPDATE ConnectorTask t SET t.enabled = :enabled WHERE t.id = :id")
    int updateEnabled(@Param("id") UUID id, @Param("enabled") boolean enabled);

    @Modifying
    @Query("UPDATE ConnectorTask t SET t.lastStartedAt = :now, t.lastError = NULL WHERE t.id = :id")
    int markStarted(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE ConnectorTask t SET t.lastError = :error WHERE t.id = :id")
    int markError(@Param("id") UUID id, @Param("error") String error);
}
