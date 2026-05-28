package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.ChannelSessionMessage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChannelSessionMessageRepository extends JpaRepository<ChannelSessionMessage, Long> {

    /**
     * Idempotent insert: skip on a duplicate (session_id, turn_idx) instead of failing.
     * Unlike a constraint violation, ON CONFLICT DO NOTHING does not poison the transaction,
     * so it safely absorbs DBOS-replay / network-retry of the same run.
     * Returns 1 if inserted, 0 if the row already existed.
     */
    @Modifying
    @Query(value = """
            INSERT INTO channel_session_messages
                (pub_id, session_id, agent_id, run_id, turn_idx, kind, message, message_json,
                 trigger_input, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens,
                 model_name, provider_name, created_at, updated_at)
            VALUES
                (:pubId, :sessionId, :agentId, :runId, :turnIdx, :kind, :message, CAST(:messageJson AS jsonb),
                 CAST(:triggerInput AS jsonb), :inputTokens, :outputTokens, :cacheReadTokens, :cacheWriteTokens,
                 :modelName, :providerName, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (session_id, turn_idx) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreConflict(@Param("pubId") UUID pubId,
                             @Param("sessionId") Long sessionId,
                             @Param("agentId") Long agentId,
                             @Param("runId") UUID runId,
                             @Param("turnIdx") Integer turnIdx,
                             @Param("kind") String kind,
                             @Param("message") String message,
                             @Param("messageJson") String messageJson,
                             @Param("triggerInput") String triggerInput,
                             @Param("inputTokens") Integer inputTokens,
                             @Param("outputTokens") Integer outputTokens,
                             @Param("cacheReadTokens") Integer cacheReadTokens,
                             @Param("cacheWriteTokens") Integer cacheWriteTokens,
                             @Param("modelName") String modelName,
                             @Param("providerName") String providerName);

    List<ChannelSessionMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    List<ChannelSessionMessage> findBySessionIdOrderByTurnIdxAsc(Long sessionId);

    List<ChannelSessionMessage> findBySessionIdOrderByTurnIdxDesc(Long sessionId, Pageable pageable);

    List<ChannelSessionMessage> findBySessionIdAndTurnIdxGreaterThanEqualOrderByTurnIdxAsc(
            Long sessionId, Integer sinceTurn);

    Optional<ChannelSessionMessage> findFirstBySessionIdAndTriggerInputIsNotNullOrderByCreatedAtDesc(
            Long sessionId);
}
