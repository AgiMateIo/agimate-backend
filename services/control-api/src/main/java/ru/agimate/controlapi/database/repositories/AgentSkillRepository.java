package ru.agimate.controlapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.controlapi.database.entities.AgentSkill;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentSkillRepository extends JpaRepository<AgentSkill, UUID> {

    List<AgentSkill> findByAgentId(UUID agentId);

    Page<AgentSkill> findByAgentId(UUID agentId, Pageable pageable);

    @Query("SELECT a.skillId FROM AgentSkill a WHERE a.agentId = :agentId")
    Page<UUID> findSkillIdsByAgentId(@Param("agentId") UUID agentId, Pageable pageable);

    Optional<AgentSkill> findByAgentIdAndSkillId(UUID agentId, UUID skillId);

    boolean existsBySkillId(UUID skillId);

    @Query("""
            SELECT a.agentId, s.id, s.name
            FROM AgentSkill a
            JOIN Skill s ON s.id = a.skillId
            WHERE a.agentId IN :agentIds AND s.deletedAt IS NULL
            ORDER BY s.name
            """)
    List<Object[]> findSkillSummariesByAgentIdIn(@Param("agentIds") Collection<UUID> agentIds);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM AgentSkill a WHERE a.skillId = :skillId")
    int deleteBySkillId(@Param("skillId") UUID skillId);
}
