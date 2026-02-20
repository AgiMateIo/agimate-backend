package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.deviceapi.database.entities.AgentTool;

import java.util.List;
import java.util.UUID;

public interface AgentToolRepository extends JpaRepository<AgentTool, Long> {

    List<AgentTool> findByApiKeyPubId(UUID apiKeyPubId);

    boolean existsByApiKeyPubIdAndToolName(UUID apiKeyPubId, String toolName);

    @Modifying
    @Query("DELETE FROM AgentTool t WHERE t.apiKeyPubId = :apiKeyPubId")
    void deleteByApiKeyPubId(@Param("apiKeyPubId") UUID apiKeyPubId);
}
