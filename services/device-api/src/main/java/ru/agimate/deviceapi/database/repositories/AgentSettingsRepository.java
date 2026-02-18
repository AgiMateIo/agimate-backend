package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.deviceapi.database.entities.AgentSettings;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentSettingsRepository extends JpaRepository<AgentSettings, Long> {

    Optional<AgentSettings> findByApiKeyPubId(UUID apiKeyPubId);

    List<AgentSettings> findByUserPubId(UUID userPubId);

    List<AgentSettings> findByUserPubIdAndTriggersAllowAllTrue(UUID userPubId);

    @Query("""
            SELECT s FROM AgentSettings s WHERE s.userPubId = :userPubId AND s.triggersTo <> 'ignore' AND (
                s.triggersAllowAll = true
                OR s.apiKeyPubId IN (
                    SELECT DISTINCT at.apiKeyPubId FROM AgentTrigger at
                    WHERE at.userPubId = :userPubId AND at.triggerName = :triggerName
                )
            )
            """)
    List<AgentSettings> findRoutableByUserPubIdAndTriggerName(
            @Param("userPubId") UUID userPubId,
            @Param("triggerName") String triggerName);
}
