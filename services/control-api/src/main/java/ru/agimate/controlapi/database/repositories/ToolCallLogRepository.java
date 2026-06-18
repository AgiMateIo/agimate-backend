package ru.agimate.controlapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.agimate.controlapi.database.entities.ToolCallLog;

import java.util.Optional;
import java.util.UUID;

public interface ToolCallLogRepository extends JpaRepository<ToolCallLog, UUID> {

    Optional<ToolCallLog> findByExternalId(String externalId);

    Optional<ToolCallLog> findByExternalIdAndAgentId(String externalId, UUID agentId);

    @Query("""
            SELECT t FROM ToolCallLog t
            WHERE t.userId = :userId
            AND (:agentId IS NULL OR t.agentId = :agentId)
            ORDER BY t.createdAt DESC
            """)
    Page<ToolCallLog> findWithFilters(UUID userId, UUID agentId, Pageable pageable);
}
