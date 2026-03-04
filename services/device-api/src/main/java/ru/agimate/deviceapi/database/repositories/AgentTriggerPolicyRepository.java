package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.deviceapi.database.entities.AgentTriggerPolicy;

import java.util.List;
import java.util.UUID;

public interface AgentTriggerPolicyRepository extends JpaRepository<AgentTriggerPolicy, UUID> {

    List<AgentTriggerPolicy> findByApiKeyPubId(UUID apiKeyPubId);

    @Query(value = """
            SELECT * FROM agent_trigger_policies
            WHERE api_key_pub_id = :apiKeyPubId
              AND (connector_name IS NULL OR connector_name = :connectorName)
              AND (connector_identity IS NULL OR CAST(:connectorIdentity AS TEXT) IS NULL OR connector_identity = :connectorIdentity)
              AND (trigger_name IS NULL OR trigger_name = :triggerName)
            """, nativeQuery = true)
    List<AgentTriggerPolicy> findMatchingPolicies(
            @Param("apiKeyPubId") UUID apiKeyPubId,
            @Param("connectorName") String connectorName,
            @Param("connectorIdentity") String connectorIdentity,
            @Param("triggerName") String triggerName
    );

    @Modifying
    @Query("DELETE FROM AgentTriggerPolicy p WHERE p.apiKeyPubId = :apiKeyPubId")
    void deleteByApiKeyPubId(@Param("apiKeyPubId") UUID apiKeyPubId);
}
