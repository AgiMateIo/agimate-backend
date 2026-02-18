package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.agimate.deviceapi.database.entities.AgentTrigger;

import java.util.List;
import java.util.UUID;

public interface AgentTriggerRepository extends JpaRepository<AgentTrigger, Long> {

    List<AgentTrigger> findByApiKeyPubId(UUID apiKeyPubId);

    List<AgentTrigger> findByTriggerName(String triggerName);

    boolean existsByApiKeyPubIdAndTriggerName(UUID apiKeyPubId, String triggerName);

    void deleteByApiKeyPubId(UUID apiKeyPubId);
}
