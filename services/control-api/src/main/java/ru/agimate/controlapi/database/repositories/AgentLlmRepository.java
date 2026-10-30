package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.enums.LlmPurpose;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentLlmRepository extends JpaRepository<AgentLlm, UUID> {

    List<AgentLlm> findAllByAgentIdOrderByPurposeAscPriorityAsc(UUID agentId);

    List<AgentLlm> findAllByAgentIdInOrderByAgentIdAscPurposeAscPriorityAsc(List<UUID> agentIds);

    List<AgentLlm> findAllByAgentIdAndPurposeOrderByPriorityAsc(UUID agentId, LlmPurpose purpose);
}
