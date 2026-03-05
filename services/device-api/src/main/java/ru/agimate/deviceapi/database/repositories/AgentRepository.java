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

    List<Agent> findByUserPubIdAndAgenticTeamId(UUID userPubId, Long agenticTeamId);

    Page<Agent> findByUserPubIdAndAgenticTeamId(UUID userPubId, Long agenticTeamId, Pageable pageable);

    boolean existsByAgenticTeamId(Long agenticTeamId);
}
