package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.agimate.deviceapi.database.entities.AgentSkill;

import java.util.Optional;
import java.util.UUID;

public interface AgentSkillRepository extends JpaRepository<AgentSkill, UUID> {

    Page<AgentSkill> findByAgentPubId(UUID agentPubId, Pageable pageable);

    Optional<AgentSkill> findByAgentPubIdAndSkillPubId(UUID agentPubId, UUID skillPubId);
}
