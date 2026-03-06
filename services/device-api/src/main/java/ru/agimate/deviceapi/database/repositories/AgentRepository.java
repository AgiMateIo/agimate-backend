package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.deviceapi.database.entities.Agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentRepository extends JpaRepository<Agent, Long> {

    Optional<Agent> findByPubId(UUID pubId);

    Optional<Agent> findByKeyId(String keyId);

    Page<Agent> findByUserPubId(UUID userPubId, Pageable pageable);

    @Query("""
            SELECT a FROM Agent a WHERE a.userPubId = :userPubId AND a.enabled = true
            AND a.pubId IN (
                SELECT DISTINCT p.agentPubId FROM AgentTriggerPolicy p
                WHERE p.effect = ru.agimate.deviceapi.abac.AccessEffect.ALLOW
                AND (p.triggerName IS NULL OR p.triggerName = :triggerName)
            )
            """)
    List<Agent> findRoutableByUserPubIdAndTriggerName(
            @Param("userPubId") UUID userPubId,
            @Param("triggerName") String triggerName);

    @Query(value = """
            WITH matched AS (
                SELECT
                    p.agent_pub_id,
                    p.effect,
                    COALESCE(p.priority,
                        (CASE WHEN p.connector_code IS NOT NULL THEN 1 ELSE 0 END) +
                        (CASE WHEN p.connector_identity IS NOT NULL THEN 1 ELSE 0 END) +
                        (CASE WHEN p.trigger_name IS NOT NULL THEN 1 ELSE 0 END)
                    ) AS specificity
                FROM agent_trigger_policies p
                JOIN agents a ON a.pub_id = p.agent_pub_id
                WHERE a.user_pub_id = :userPubId
                  AND a.enabled = true
                  AND (p.connector_code IS NULL OR p.connector_code = :connectorCode)
                  AND (p.connector_identity IS NULL OR CAST(:connectorIdentity AS TEXT) IS NULL OR p.connector_identity = :connectorIdentity)
                  AND (p.trigger_name IS NULL OR p.trigger_name = :triggerName)
            ),
            max_spec AS (
                SELECT agent_pub_id, MAX(specificity) AS max_specificity
                FROM matched
                GROUP BY agent_pub_id
            ),
            winning AS (
                SELECT m.agent_pub_id,
                       bool_or(m.effect = 'DENY') AS has_deny
                FROM matched m
                JOIN max_spec ms ON m.agent_pub_id = ms.agent_pub_id
                                AND m.specificity = ms.max_specificity
                GROUP BY m.agent_pub_id
            )
            SELECT a.* FROM agents a
            JOIN winning w ON a.pub_id = w.agent_pub_id
            WHERE NOT w.has_deny
            """, nativeQuery = true)
    List<Agent> findAllowedAgents(
            @Param("userPubId") UUID userPubId,
            @Param("connectorCode") String connectorCode,
            @Param("connectorIdentity") String connectorIdentity,
            @Param("triggerName") String triggerName);

    List<Agent> findByUserPubIdAndAgenticTeamId(UUID userPubId, Long agenticTeamId);

    Page<Agent> findByUserPubIdAndAgenticTeamId(UUID userPubId, Long agenticTeamId, Pageable pageable);

    boolean existsByAgenticTeamId(Long agenticTeamId);
}
