package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.agimate.deviceapi.database.entities.AgentSettings;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentSettingsRepository extends JpaRepository<AgentSettings, Long> {

    Optional<AgentSettings> findByApiKeyPubId(UUID apiKeyPubId);

    List<AgentSettings> findByUserPubId(UUID userPubId);
}
