package ru.agimate.controlapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.controlapi.database.entities.Agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentRepository extends JpaRepository<Agent, UUID> {

    Optional<Agent> findByKeyId(String keyId);

    Page<Agent> findByUserId(UUID userId, Pageable pageable);

    @Query("""
            SELECT a FROM Agent a
            WHERE a.userId = :userId
              AND (:agenticTeamId IS NULL OR a.agenticTeamId = :agenticTeamId)
              AND (CAST(:search AS string) IS NULL
                   OR LOWER(a.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(a.description) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
            """)
    Page<Agent> searchForUser(
            @Param("userId") UUID userId,
            @Param("agenticTeamId") UUID agenticTeamId,
            @Param("search") String search,
            Pageable pageable);

    @Query("""
            SELECT a FROM Agent a WHERE a.userId = :userId AND a.enabled = true
            AND a.id IN (
                SELECT DISTINCT p.agentId FROM AgentTriggerPolicy p
                WHERE p.effect = ru.agimate.controlapi.abac.AccessEffect.ALLOW
                AND (p.triggerName IS NULL OR p.triggerName = :triggerName)
            )
            """)
    List<Agent> findRoutableByUserIdAndTriggerName(
            @Param("userId") UUID userId,
            @Param("triggerName") String triggerName);

    @Query(value = """
            WITH matched AS (
                SELECT
                    p.agent_id,
                    p.effect,
                    COALESCE(p.priority,
                        (CASE WHEN p.connector_code IS NOT NULL THEN 1 ELSE 0 END) +
                        (CASE WHEN p.connector_identity IS NOT NULL THEN 1 ELSE 0 END) +
                        (CASE WHEN p.trigger_name IS NOT NULL THEN 1 ELSE 0 END)
                    ) AS specificity
                FROM agent_trigger_policies p
                JOIN agents a ON a.id = p.agent_id
                WHERE a.user_id = :userId
                  AND a.enabled = true
                  AND (p.connector_code IS NULL OR p.connector_code = :connectorCode)
                  AND (p.connector_identity IS NULL OR CAST(:connectorIdentity AS TEXT) IS NULL OR p.connector_identity = :connectorIdentity)
                  AND (p.trigger_name IS NULL OR p.trigger_name = :triggerName)
            ),
            max_spec AS (
                SELECT agent_id, MAX(specificity) AS max_specificity
                FROM matched
                GROUP BY agent_id
            ),
            winning AS (
                SELECT m.agent_id,
                       bool_or(m.effect = 'DENY') AS has_deny
                FROM matched m
                JOIN max_spec ms ON m.agent_id = ms.agent_id
                                AND m.specificity = ms.max_specificity
                GROUP BY m.agent_id
            )
            SELECT a.* FROM agents a
            JOIN winning w ON a.id = w.agent_id
            WHERE NOT w.has_deny
            """, nativeQuery = true)
    List<Agent> findAllowedAgents(
            @Param("userId") UUID userId,
            @Param("connectorCode") String connectorCode,
            @Param("connectorIdentity") String connectorIdentity,
            @Param("triggerName") String triggerName);

    @Query(value = """
            WITH matched AS (
                SELECT
                    p.agent_id,
                    p.effect,
                    COALESCE(p.priority,
                        (CASE WHEN p.connector_code IS NOT NULL THEN 1 ELSE 0 END) +
                        (CASE WHEN p.connector_identity IS NOT NULL THEN 1 ELSE 0 END) +
                        (CASE WHEN p.trigger_name IS NOT NULL THEN 1 ELSE 0 END)
                    ) AS specificity
                FROM agent_trigger_policies p
                JOIN agents a ON a.id = p.agent_id
                JOIN agentic_teams t ON a.agentic_team_id = t.id
                WHERE a.user_id = :userId
                  AND a.enabled = true
                  AND t.id = :agenticTeamId
                  AND (p.connector_code IS NULL OR p.connector_code = :connectorCode)
                  AND (p.connector_identity IS NULL OR CAST(:connectorIdentity AS TEXT) IS NULL OR p.connector_identity = :connectorIdentity)
                  AND (p.trigger_name IS NULL OR p.trigger_name = :triggerName)
            ),
            max_spec AS (
                SELECT agent_id, MAX(specificity) AS max_specificity
                FROM matched
                GROUP BY agent_id
            ),
            winning AS (
                SELECT m.agent_id,
                       bool_or(m.effect = 'DENY') AS has_deny
                FROM matched m
                JOIN max_spec ms ON m.agent_id = ms.agent_id
                                AND m.specificity = ms.max_specificity
                GROUP BY m.agent_id
            )
            SELECT a.* FROM agents a
            JOIN winning w ON a.id = w.agent_id
            WHERE NOT w.has_deny
            """, nativeQuery = true)
    List<Agent> findAllowedAgentsForTeamId(
            @Param("userId") UUID userId,
            @Param("agenticTeamId") UUID agenticTeamId,
            @Param("connectorCode") String connectorCode,
            @Param("connectorIdentity") String connectorIdentity,
            @Param("triggerName") String triggerName);

    List<Agent> findByUserIdAndAgenticTeamId(UUID userId, UUID agenticTeamId);

    Page<Agent> findByUserIdAndAgenticTeamId(UUID userId, UUID agenticTeamId, Pageable pageable);

    boolean existsByAgenticTeamId(UUID agenticTeamId);

    @Query("""
            SELECT a FROM Agent a
            WHERE a.userId = :userId
              AND a.id IN (
                  SELECT s.agentId FROM AgentSkill s WHERE s.skillId = :skillId
              )
              AND (CAST(:search AS string) IS NULL
                   OR LOWER(a.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(a.instructions) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
            """)
    Page<Agent> findBySkillId(
            @Param("skillId") UUID skillId,
            @Param("userId") UUID userId,
            @Param("search") String search,
            Pageable pageable);
}
