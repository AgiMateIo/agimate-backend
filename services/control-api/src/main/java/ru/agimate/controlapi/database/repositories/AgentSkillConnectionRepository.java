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

    /**
     * A bulk statement, not a derived delete: the derived one queues {@code em.remove} until flush, and
     * Hibernate flushes inserts before deletes — re-storing the same key right after would hit
     * {@code uq_agent_skill_connections_agent_skill_id_connector_key}.
     */
    @Modifying
    @Query("DELETE FROM AgentSkillConnection c WHERE c.agentSkillId = :agentSkillId")
    void deleteByAgentSkillId(@Param("agentSkillId") UUID agentSkillId);
}
