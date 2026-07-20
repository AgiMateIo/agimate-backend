package ru.agimate.controlapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.database.entities.ToolCallLog;

import java.util.Optional;
import java.util.UUID;

public interface ToolCallLogRepository extends JpaRepository<ToolCallLog, UUID> {

    Optional<ToolCallLog> findByExternalId(String externalId);

    Optional<ToolCallLog> findByExternalIdAndAgentId(String externalId, UUID agentId);

    /**
     * {@code status} — строка {@link ru.agimate.controlapi.controller.manage.dto.ToolCallStatus}
     * ({@code SUCCESS}/{@code ERROR}/{@code PENDING}), выводится из {@code finish_at}/{@code error}.
     * {@code name} — регистронезависимый подстрочный поиск по имени тула.
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
                 OR (:status = 'ERROR'   AND t.finishAt IS NOT NULL AND t.error IS NOT NULL)
                 OR (:status = 'PENDING' AND t.finishAt IS NULL AND t.error IS NULL))
            ORDER BY t.createdAt DESC
            """)
    Page<ToolCallLog> findWithFilters(UUID userId, UUID agentId, String connectorCode,
                                      String connectionId, AccessEffect accessEffect,
                                      String name, String status, Pageable pageable);
}
