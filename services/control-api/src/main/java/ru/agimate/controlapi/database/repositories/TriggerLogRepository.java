package ru.agimate.controlapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.controlapi.database.entities.TriggerLog;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.projections.AgentRunProjection;
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
            """)
    Page<TriggerLogWithAgentsCountProjection> findByUserIdWithFilters(UUID userId, String connectorCode, Pageable pageable);

    /**
     * Per-agent листинг: прогоны триггеров у конкретного агента ({@code agent_runs}
     * ⋈ {@code trigger_logs}). {@code status} — {@link RunStatus} прогона (реальная колонка).
     * {@code name} — регистронезависимый подстрочный поиск по имени триггера.
     */
    @Query("""
            SELECT a.id AS id, tl.id AS triggerLogId, tl.connectorCode AS connectorCode,
                   tl.connectionId AS connectionId, tl.externalId AS externalId, tl.name AS name,
                   tl.occurredAt AS occurredAt, tl.input AS input,
                   a.status AS status, a.result AS result, a.error AS error,
                   a.sessionId AS sessionId, a.lastActivityAt AS lastActivityAt, a.createdAt AS createdAt
            FROM AgentRun a
            JOIN a.triggerLog tl
            WHERE tl.userId = :userId
            AND a.agent.id = :agentId
            AND (:connectorCode IS NULL OR tl.connectorCode = :connectorCode)
            AND (:connectionId IS NULL OR tl.connectionId = :connectionId)
            AND (:name IS NULL OR LOWER(tl.name) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%')))
            AND (:status IS NULL OR a.status = :status)
            ORDER BY a.createdAt DESC
            """)
    Page<AgentRunProjection> findAgentRunsWithFilters(UUID userId, UUID agentId,
                                                                String connectorCode, String connectionId,
                                                                String name, RunStatus status, Pageable pageable);

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
