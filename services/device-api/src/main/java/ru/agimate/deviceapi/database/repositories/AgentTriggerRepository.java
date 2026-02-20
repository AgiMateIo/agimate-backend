package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.deviceapi.database.entities.AgentTrigger;

import java.util.List;
import java.util.UUID;

public interface AgentTriggerRepository extends JpaRepository<AgentTrigger, Long> {

    List<AgentTrigger> findByApiKeyPubId(UUID apiKeyPubId);

    List<AgentTrigger> findByTriggerName(String triggerName);

    boolean existsByApiKeyPubIdAndTriggerName(UUID apiKeyPubId, String triggerName);

    @Modifying
    @Query("DELETE FROM AgentTrigger t WHERE t.apiKeyPubId = :apiKeyPubId")
    void deleteByApiKeyPubId(@Param("apiKeyPubId") UUID apiKeyPubId);

    @Query("SELECT DISTINCT at.apiKeyPubId FROM AgentTrigger at WHERE at.userPubId = :userPubId AND at.triggerName = :triggerName")
    List<UUID> findApiKeyPubIdsByUserPubIdAndTriggerName(@Param("userPubId") UUID userPubId, @Param("triggerName") String triggerName);
}
