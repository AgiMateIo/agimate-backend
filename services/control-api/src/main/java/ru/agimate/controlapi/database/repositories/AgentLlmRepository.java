package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.AgentLlm;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentLlmRepository extends JpaRepository<AgentLlm, UUID> {

    List<AgentLlm> findAllByAgentIdOrderByName(UUID agentId);

    List<AgentLlm> findAllByAgentIdInOrderByAgentIdAscNameAsc(List<UUID> agentIds);

    Optional<AgentLlm> findByAgentIdAndName(UUID agentId, String name);

    boolean existsByAgentIdAndName(UUID agentId, String name);
}
