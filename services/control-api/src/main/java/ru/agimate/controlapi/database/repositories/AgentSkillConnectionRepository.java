package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.AgentSkillConnection;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentSkillConnectionRepository extends JpaRepository<AgentSkillConnection, UUID> {

    List<AgentSkillConnection> findByAgentSkillId(UUID agentSkillId);

    List<AgentSkillConnection> findByAgentSkillIdIn(List<UUID> agentSkillIds);

    @Modifying
    void deleteByAgentSkillId(UUID agentSkillId);

    /**
     * How many of the agent's skills point at each connection — the «used by N skills» counter of the
     * connection listing. Keyed by connection, so a connection nobody points at is simply absent.
     */
    @Query("""
            SELECT link.connectionId, COUNT(link)
            FROM AgentSkillConnection link, AgentSkill binding
            WHERE link.agentSkillId = binding.id AND binding.agentId = :agentId
            GROUP BY link.connectionId
            """)
    List<Object[]> countByConnectionForAgent(@Param("agentId") UUID agentId);
}
