package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.enums.LlmPurpose;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentLlmRepository extends JpaRepository<AgentLlm, UUID> {

    List<AgentLlm> findAllByAgentIdOrderByPurpose(UUID agentId);

    List<AgentLlm> findAllByAgentIdInOrderByAgentIdAscPurposeAsc(List<UUID> agentIds);

    Optional<AgentLlm> findByAgentIdAndPurpose(UUID agentId, LlmPurpose purpose);

    boolean existsByAgentIdAndPurpose(UUID agentId, LlmPurpose purpose);
}
