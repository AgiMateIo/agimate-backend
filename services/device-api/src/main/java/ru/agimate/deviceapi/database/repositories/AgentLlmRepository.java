package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.AgentLlm;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentLlmRepository extends JpaRepository<AgentLlm, Long> {

    List<AgentLlm> findAllByAgentPubIdOrderByName(UUID agentPubId);

    List<AgentLlm> findAllByAgentPubIdInOrderByAgentPubIdAscNameAsc(List<UUID> agentPubIds);

    Optional<AgentLlm> findByAgentPubIdAndName(UUID agentPubId, String name);

    boolean existsByAgentPubIdAndName(UUID agentPubId, String name);
}
