package ru.agimate.deviceapi.database.repositories;

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

    List<Agent> findByUserPubId(UUID userPubId);

    @Query("""
            SELECT a FROM Agent a WHERE a.userPubId = :userPubId AND a.triggersTo <> 'ignore' AND (
                a.triggersAllowAll = true
                OR a.pubId IN (
                    SELECT DISTINCT p.agentPubId FROM AgentTriggerPolicy p
                    WHERE p.effect = ru.agimate.deviceapi.abac.AccessEffect.ALLOW
                    AND (p.triggerName IS NULL OR p.triggerName = :triggerName)
                )
            )
            """)
    List<Agent> findRoutableByUserPubIdAndTriggerName(
            @Param("userPubId") UUID userPubId,
            @Param("triggerName") String triggerName);

    List<Agent> findByUserPubIdAndAgenticTeamId(UUID userPubId, Long agenticTeamId);

    boolean existsByAgenticTeamId(Long agenticTeamId);
}
