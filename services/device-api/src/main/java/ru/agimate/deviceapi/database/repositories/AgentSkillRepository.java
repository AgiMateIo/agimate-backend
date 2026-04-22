package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.deviceapi.database.entities.AgentSkill;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentSkillRepository extends JpaRepository<AgentSkill, UUID> {

    List<AgentSkill> findByAgentPubId(UUID agentPubId);

    Page<AgentSkill> findByAgentPubId(UUID agentPubId, Pageable pageable);

    @Query("SELECT a.skillPubId FROM AgentSkill a WHERE a.agentPubId = :agentPubId")
    Page<UUID> findSkillPubIdsByAgentPubId(@Param("agentPubId") UUID agentPubId, Pageable pageable);

    Optional<AgentSkill> findByAgentPubIdAndSkillPubId(UUID agentPubId, UUID skillPubId);

    @Query("""
            SELECT a.agentPubId, s.pubId, s.name
            FROM AgentSkill a
            JOIN Skill s ON s.pubId = a.skillPubId
            WHERE a.agentPubId IN :agentPubIds AND s.deletedAt IS NULL
            ORDER BY s.name
            """)
    List<Object[]> findSkillSummariesByAgentPubIdIn(@Param("agentPubIds") Collection<UUID> agentPubIds);
}
