package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.controlapi.database.entities.AgentStarter;

import java.util.List;
import java.util.UUID;

public interface AgentStarterRepository extends JpaRepository<AgentStarter, UUID> {

    List<AgentStarter> findByAgentId(UUID agentId);

    boolean existsByAgentIdAndAgentSkillIdIsNull(UUID agentId);

    /**
     * Idempotent hide: a second click — or two tabs — must not trip the unique key. Native SQL bypasses
     * {@code @UpdateTimestamp}, so {@code updated_at} is set by hand.
     */
    @Modifying
    @Query(value = """
            INSERT INTO agent_starters (agent_id, agent_skill_id, hidden, text)
            VALUES (:agentId, :agentSkillId, TRUE, :text)
            ON CONFLICT (agent_skill_id, text) DO UPDATE SET hidden = TRUE, updated_at = CURRENT_TIMESTAMP
            """, nativeQuery = true)
    void hideSkillStarter(@Param("agentId") UUID agentId, @Param("agentSkillId") UUID agentSkillId,
                          @Param("text") String text);

    /** Restoring is forgetting: a skill starter's row exists only to remember that it was hidden. */
    @Modifying
    @Query("DELETE FROM AgentStarter s WHERE s.agentId = :agentId AND s.agentSkillId IS NOT NULL")
    int deleteSkillStarterStates(@Param("agentId") UUID agentId);
}
