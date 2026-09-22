package ru.agimate.controlapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.AgentRunTurn;
import ru.agimate.controlapi.database.enums.AgentTurnRole;

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
                (run_id, session_id, agent_id, turn_index, role, text, thinking_text,
                 tool_calls, tool_results, finish_reason, model, call_id, created_at, updated_at)
            VALUES
                (:runId, :sessionId, :agentId, :turnIndex, :role, :text, :thinkingText,
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
                             @Param("thinkingText") String thinkingText,
                             @Param("toolCalls") String toolCalls,
                             @Param("toolResults") String toolResults,
                             @Param("finishReason") String finishReason,
                             @Param("model") String model,
                             @Param("callId") String callId);

    /**
     * The run's own turns, oldest first. A compaction summary sits on the run at {@link AgentRunTurn#SUMMARY_TURN_INDEX}
     * but belongs to the session — no transcript of the run shows it.
     */
    @Query("SELECT t FROM AgentRunTurn t WHERE t.runId = :runId AND t.turnIndex >= 0 ORDER BY t.turnIndex")
    List<AgentRunTurn> findRunTurns(@Param("runId") UUID runId);

    /** The transcript for the UI: newest turn first, a page at a time, without the session's summary. */
    @Query("SELECT t FROM AgentRunTurn t WHERE t.runId = :runId AND t.turnIndex >= 0 ORDER BY t.turnIndex DESC")
    Page<AgentRunTurn> findRunTurnsNewestFirst(@Param("runId") UUID runId, Pageable pageable);

    /** History of a session: the turns of the runs picked by the window, in the ledger's own order. */
    List<AgentRunTurn> findByRunIdInOrderByRunIdAscTurnIndexAsc(List<UUID> runIds);

    @Query("SELECT COUNT(t) FROM AgentRunTurn t WHERE t.runId = :runId AND t.turnIndex >= 0")
    long countRunTurns(@Param("runId") UUID runId);

    /** The newest compaction summary of the session — what the history window starts from. */
    @Query("""
            SELECT t FROM AgentRunTurn t
            WHERE t.sessionId = :sessionId
              AND t.role = ru.agimate.controlapi.database.enums.AgentTurnRole.SYSTEM
            ORDER BY t.createdAt DESC
            LIMIT 1
            """)
    Optional<AgentRunTurn> findLatestSummary(@Param("sessionId") UUID sessionId);

    /**
     * Messages of every session of the agent whose text contains {@code pattern} — the
     * {@code sessions.search_messages} tool. Only what was said: summaries and tool output are not
     * searched. {@code pattern} is an ILIKE pattern with its wildcards already escaped by the caller.
     */
    @Query(value = """
            SELECT * FROM agent_run_turns
            WHERE agent_id = :agentId
              AND role IN ('USER', 'ASSISTANT')
              AND text ILIKE :pattern ESCAPE '\\'
            ORDER BY created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<AgentRunTurn> searchText(@Param("agentId") UUID agentId, @Param("pattern") String pattern,
                                  @Param("limit") int limit);

    Optional<AgentRunTurn> findByRunIdAndTurnIndex(UUID runId, int turnIndex);

    /** The run's first turn of a role — its first ASSISTANT turn carries the call id of the run's first model call. */
    Optional<AgentRunTurn> findFirstByRunIdAndRoleAndTurnIndexGreaterThanEqualOrderByTurnIndexAsc(
            UUID runId, AgentTurnRole role, int turnIndex);

    /** The last turn of a run — its {@code turn_index} closes the contiguity check, its role the pairing one. */
    Optional<AgentRunTurn> findFirstByRunIdOrderByTurnIndexDesc(UUID runId);
}
