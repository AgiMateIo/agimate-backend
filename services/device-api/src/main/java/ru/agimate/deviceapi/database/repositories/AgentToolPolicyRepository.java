package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.deviceapi.database.entities.AgentToolPolicy;

import java.util.List;
import java.util.UUID;

public interface AgentToolPolicyRepository extends JpaRepository<AgentToolPolicy, UUID> {

    List<AgentToolPolicy> findByApiKeyPubId(UUID apiKeyPubId);

    @Query(value = """
            SELECT * FROM agent_tool_policies
            WHERE api_key_pub_id = :apiKeyPubId
              AND (connector_name IS NULL OR connector_name = :connectorName)
              AND (connector_identity IS NULL OR CAST(:connectorIdentity AS TEXT) IS NULL OR connector_identity = :connectorIdentity)
              AND (tool_name IS NULL OR tool_name = :toolName)
            """, nativeQuery = true)
    List<AgentToolPolicy> findMatchingPolicies(
            @Param("apiKeyPubId") UUID apiKeyPubId,
            @Param("connectorName") String connectorName,
            @Param("connectorIdentity") String connectorIdentity,
            @Param("toolName") String toolName
    );

    @Modifying
    @Query("DELETE FROM AgentToolPolicy p WHERE p.apiKeyPubId = :apiKeyPubId")
    void deleteByApiKeyPubId(@Param("apiKeyPubId") UUID apiKeyPubId);
}
