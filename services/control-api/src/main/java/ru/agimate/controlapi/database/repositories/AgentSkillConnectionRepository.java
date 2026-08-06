package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
}
