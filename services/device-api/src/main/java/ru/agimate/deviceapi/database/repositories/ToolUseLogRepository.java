package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.agimate.deviceapi.database.entities.ToolUseLog;

import java.util.Optional;
import java.util.UUID;

public interface ToolUseLogRepository extends JpaRepository<ToolUseLog, UUID> {

    Optional<ToolUseLog> findByToolUseId(String toolUseId);

    Optional<ToolUseLog> findByToolUseIdAndAgentId(String toolUseId, UUID agentId);

    @Query("""
            SELECT t FROM ToolUseLog t
            WHERE t.userPubId = :userPubId
            AND (:agentId IS NULL OR t.agentId = :agentId)
            ORDER BY t.createdAt DESC
            """)
    Page<ToolUseLog> findWithFilters(UUID userPubId, UUID agentId, Pageable pageable);
}
