package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.AgentRunTurn;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentRunTurnRepository extends JpaRepository<AgentRunTurn, UUID> {

    /**
     * Idempotent insert: skip on a duplicate (run_id, turn_index) instead of failing.
     * ON CONFLICT DO NOTHING does not poison the transaction, so it safely absorbs a DBOS replay or
     * a retry of the same turn. {@code id} comes from the database default ({@code uuidv7()}).
     * Returns 1 if inserted, 0 if the row already existed.
     */
    @Modifying
    @Query(value = """
            INSERT INTO agent_run_turns
                (run_id, session_id, agent_id, turn_index, role, text, thinking, thinking_text,
                 tool_calls, tool_results, finish_reason, model, call_id, created_at, updated_at)
            VALUES
                (:runId, :sessionId, :agentId, :turnIndex, :role, :text, :thinking, :thinkingText,
                 CAST(:toolCalls AS jsonb), CAST(:toolResults AS jsonb), :finishReason, :model, :callId,
                 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (run_id, turn_index) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreConflict(@Param("runId") UUID runId,
                             @Param("sessionId") UUID sessionId,
                             @Param("agentId") UUID agentId,
                             @Param("turnIndex") int turnIndex,
                             @Param("role") String role,
                             @Param("text") String text,
                             @Param("thinking") boolean thinking,
                             @Param("thinkingText") String thinkingText,
                             @Param("toolCalls") String toolCalls,
                             @Param("toolResults") String toolResults,
                             @Param("finishReason") String finishReason,
                             @Param("model") String model,
                             @Param("callId") String callId);

    List<AgentRunTurn> findByRunIdOrderByTurnIndexAsc(UUID runId);

    /** History of a session: the turns of the runs picked by the window, in the ledger's own order. */
    List<AgentRunTurn> findByRunIdInOrderByRunIdAscTurnIndexAsc(List<UUID> runIds);

    long countByRunId(UUID runId);

    /** The last turn of a run — its {@code turn_index} closes the contiguity check, its role the pairing one. */
    Optional<AgentRunTurn> findFirstByRunIdOrderByTurnIndexDesc(UUID runId);
}
