package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.LlmUsageLog;
import ru.agimate.controlapi.database.projections.RunUsageProjection;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface LlmUsageLogRepository extends JpaRepository<LlmUsageLog, UUID> {

    /**
     * Idempotent insert of a journal record: a repeat by {@code call_id} is silently ignored.
     * {@code id}/{@code created_at}/{@code updated_at} come from database defaults.
     *
     * @return 1 — a new row (the counters need incrementing), 0 — a duplicate report
     */
    @Modifying
    @Query(value = """
            INSERT INTO llm_usage_log (call_id, run_id, agent_id, user_id, llm_provider_id, model,
                                       input_tokens, output_tokens, cache_read_tokens, cache_write_tokens)
            VALUES (:callId, :runId, :agentId, :userId, :providerId, :model,
                    :inputTokens, :outputTokens, :cacheReadTokens, :cacheWriteTokens)
            ON CONFLICT (call_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreDuplicate(@Param("callId") String callId,
                              @Param("runId") UUID runId,
                              @Param("agentId") UUID agentId,
                              @Param("userId") UUID userId,
                              @Param("providerId") UUID providerId,
                              @Param("model") String model,
                              @Param("inputTokens") int inputTokens,
                              @Param("outputTokens") int outputTokens,
                              @Param("cacheReadTokens") Integer cacheReadTokens,
                              @Param("cacheWriteTokens") Integer cacheWriteTokens);

    /**
     * Token spend of the given runs. A page of the runs listing is enriched with one call of this
     * rather than a correlated aggregate per row — and it has to be a separate query anyway: the
     * listing selects the trigger's JSONB payload, and Postgres has no equality operator for
     * {@code jsonb}, so that projection cannot carry a GROUP BY.
     */
    @Query("""
            SELECT u.runId AS runId,
                   COALESCE(SUM(u.inputTokens), 0) AS inputTokens,
                   COALESCE(SUM(u.outputTokens), 0) AS outputTokens,
                   COALESCE(SUM(u.cacheReadTokens), 0) AS cacheReadTokens,
                   COALESCE(SUM(u.cacheWriteTokens), 0) AS cacheWriteTokens,
                   COUNT(u) AS calls
            FROM LlmUsageLog u
            WHERE u.runId IN :runIds
            GROUP BY u.runId
            """)
    List<RunUsageProjection> sumByRunIds(@Param("runIds") Collection<UUID> runIds);

    /** Per-turn spend: a turn carries the id of the LLM call that produced it. */
    List<LlmUsageLog> findByCallIdIn(Collection<String> callIds);
}
