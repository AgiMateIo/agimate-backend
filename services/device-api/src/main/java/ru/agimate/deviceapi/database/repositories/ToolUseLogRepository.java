package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.agimate.deviceapi.database.entities.ToolUseLog;

import java.util.Optional;
import java.util.UUID;

public interface ToolUseLogRepository extends JpaRepository<ToolUseLog, Long> {

    Optional<ToolUseLog> findByToolUseId(String toolUseId);

    @Query("""
            SELECT t FROM ToolUseLog t
            WHERE t.userPubId = :userPubId
            AND (:apiKeyPubId IS NULL OR t.apiKeyPubId = :apiKeyPubId)
            ORDER BY t.createdAt DESC
            """)
    Page<ToolUseLog> findWithFilters(UUID userPubId, UUID apiKeyPubId, Pageable pageable);
}
