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
            SELECT t.id AS id, t.connectorCode AS connectorCode, t.identity AS identity,
                   t.triggerId AS triggerId, t.triggerName AS triggerName,
                   t.occurredAt AS occurredAt, t.triggerInput AS triggerInput, t.createdAt AS createdAt,
                   SIZE(t.triggerLogAgents) AS agentsCount
            FROM TriggerLog t
            WHERE t.userId = :userId
            AND (:connectorCode IS NULL OR t.connectorCode = :connectorCode)
            """)
    Page<TriggerLogWithAgentsCountProjection> findByUserIdWithFilters(UUID userId, String connectorCode, Pageable pageable);

    @Query(value = """
            SELECT tl.* FROM trigger_logs tl
            WHERE tl.user_id = :userId
              AND tl.created_at >= :since
              AND tl.trigger_input::text ILIKE CONCAT('%', :code, '%')
            ORDER BY tl.created_at ASC
            LIMIT 1
            """, nativeQuery = true)
    Optional<TriggerLog> findFirstByUserAndPayloadContaining(
            @Param("userId") UUID userId,
            @Param("code") String code,
            @Param("since") LocalDateTime since);
}
