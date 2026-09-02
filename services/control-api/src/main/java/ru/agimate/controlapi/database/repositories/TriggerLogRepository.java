package ru.agimate.controlapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.controlapi.database.entities.TriggerLog;
import ru.agimate.controlapi.database.projections.TriggerLogWithAgentsCountProjection;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface TriggerLogRepository extends JpaRepository<TriggerLog, UUID> {

    @Query("""
            SELECT t.id AS id, t.connectorCode AS connectorCode, t.connectionId AS connectionId,
                   t.externalId AS externalId, t.name AS name,
                   t.occurredAt AS occurredAt, t.input AS input, t.createdAt AS createdAt,
                   SIZE(t.agentRuns) AS agentsCount
            FROM TriggerLog t
            WHERE t.userId = :userId
            AND (:connectorCode IS NULL OR t.connectorCode = :connectorCode)
            AND (:agentId IS NULL OR EXISTS (
                    SELECT 1 FROM AgentRun ar WHERE ar.triggerLog = t AND ar.agent.id = :agentId))
            AND (:since IS NULL OR t.occurredAt >= :since)
            AND (:until IS NULL OR t.occurredAt <= :until)
            """)
    Page<TriggerLogWithAgentsCountProjection> findByUserIdWithFilters(
            @Param("userId") UUID userId,
            @Param("connectorCode") String connectorCode,
            @Param("agentId") UUID agentId,
            @Param("since") LocalDateTime since,
            @Param("until") LocalDateTime until,
            Pageable pageable);

    @Query(value = """
            SELECT tl.* FROM trigger_logs tl
            WHERE tl.user_id = :userId
              AND tl.created_at >= :since
              AND tl.input::text ILIKE CONCAT('%', :code, '%')
            ORDER BY tl.created_at ASC
            LIMIT 1
            """, nativeQuery = true)
    Optional<TriggerLog> findFirstByUserAndPayloadContaining(
            @Param("userId") UUID userId,
            @Param("code") String code,
            @Param("since") LocalDateTime since);
}
