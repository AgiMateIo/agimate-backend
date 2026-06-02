package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.deviceapi.database.entities.AgentTriggerPolicy;
import ru.agimate.deviceapi.database.projections.PolicyResolutionResult;

import java.util.List;
import java.util.UUID;

public interface AgentTriggerPolicyRepository extends JpaRepository<AgentTriggerPolicy, UUID> {

    List<AgentTriggerPolicy> findByAgentId(UUID agentId);

    List<AgentTriggerPolicy> findByUserPubIdAndAgentId(UUID userPubId, UUID agentId);

    Page<AgentTriggerPolicy> findByUserPubIdAndAgentId(UUID userPubId, UUID agentId, Pageable pageable);

    @Query(value = """
            SELECT * FROM agent_trigger_policies
            WHERE agent_id = :agentId
              AND (connector_code IS NULL OR connector_code = :connectorCode)
              AND (connector_identity IS NULL OR CAST(:connectorIdentity AS TEXT) IS NULL OR connector_identity = :connectorIdentity)
              AND (trigger_name IS NULL OR trigger_name = :triggerName)
            """, nativeQuery = true)
    List<AgentTriggerPolicy> findMatchingPolicies(
            @Param("agentId") UUID agentId,
            @Param("connectorCode") String connectorCode,
            @Param("connectorIdentity") String connectorIdentity,
            @Param("triggerName") String triggerName
    );

    @Query(value = """
            WITH matched AS (
                SELECT
                    id,
                    effect,
                    COALESCE(priority,
                        (CASE WHEN connector_code IS NOT NULL THEN 1 ELSE 0 END) +
                        (CASE WHEN connector_identity IS NOT NULL THEN 1 ELSE 0 END) +
                        (CASE WHEN trigger_name IS NOT NULL THEN 1 ELSE 0 END) +
                        (CASE WHEN input_filter IS NOT NULL THEN 1 ELSE 0 END)
                    ) AS specificity
                FROM agent_trigger_policies
                WHERE agent_id = :agentId
                  AND (connector_code IS NULL OR connector_code = :connectorCode)
                  AND (connector_identity IS NULL OR CAST(:connectorIdentity AS TEXT) IS NULL OR connector_identity = :connectorIdentity)
                  AND (trigger_name IS NULL OR trigger_name = :triggerName)
            ),
            max_spec AS (
                SELECT MAX(specificity) AS max_specificity FROM matched
            )
            SELECT
                m.id AS id,
                m.effect AS effect,
                m.specificity AS specificity
            FROM matched m
            JOIN max_spec ms ON m.specificity = ms.max_specificity
            ORDER BY
                CASE WHEN m.effect = 'DENY' THEN 0 ELSE 1 END
            LIMIT 1
            """, nativeQuery = true)
    PolicyResolutionResult resolveAccess(
            @Param("agentId") UUID agentId,
            @Param("connectorCode") String connectorCode,
            @Param("connectorIdentity") String connectorIdentity,
            @Param("triggerName") String triggerName
    );

    @Query(value = """
            SELECT * FROM agent_trigger_policies
            WHERE agent_id = :agentId
              AND connector_code IS NOT DISTINCT FROM CAST(:connectorCode AS TEXT)
              AND connector_identity IS NOT DISTINCT FROM CAST(:connectorIdentity AS TEXT)
              AND trigger_name IS NOT DISTINCT FROM CAST(:triggerName AS TEXT)
              AND effect = :effect
            """, nativeQuery = true)
    AgentTriggerPolicy findByCompositeKey(
            @Param("agentId") UUID agentId,
            @Param("connectorCode") String connectorCode,
            @Param("connectorIdentity") String connectorIdentity,
            @Param("triggerName") String triggerName,
            @Param("effect") String effect
    );

    List<AgentTriggerPolicy> findByAgentIdAndSource(UUID agentId, String source);

    List<AgentTriggerPolicy> findByChannelIdIn(java.util.Collection<UUID> channelIds);

    @Modifying
    @Query("DELETE FROM AgentTriggerPolicy p WHERE p.agentId = :agentId")
    void deleteByAgentId(@Param("agentId") UUID agentId);

    @Modifying
    @Query("DELETE FROM AgentTriggerPolicy p WHERE p.channelId = :channelId")
    void deleteByChannelId(@Param("channelId") UUID channelId);
}
