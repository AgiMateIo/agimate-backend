package ru.agimate.controlapi.database.repositories;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.ChannelSessionMessage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChannelSessionMessageRepository extends JpaRepository<ChannelSessionMessage, UUID> {

    /**
     * Idempotent insert: skip on a duplicate (run_id, seq) instead of failing.
     * Unlike a constraint violation, ON CONFLICT DO NOTHING does not poison the transaction,
     * so it safely absorbs DBOS-replay / network-retry of the same run.
     * The {@code id} primary key is populated by the database default ({@code uuidv7()}).
     * Returns 1 if inserted, 0 if the row already existed.
     */
    @Modifying
    @Query(value = """
            INSERT INTO channel_session_messages
                (session_id, agent_id, run_id, seq, kind, progress_type, message,
                 message_json, trigger_input, completed, created_at, updated_at)
            VALUES
                (:sessionId, :agentId, :runId, :seq, :kind, :progressType, :message,
                 CAST(:messageJson AS jsonb), CAST(:triggerInput AS jsonb), false,
                 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (run_id, seq) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreConflict(@Param("sessionId") UUID sessionId,
                             @Param("agentId") UUID agentId,
                             @Param("runId") UUID runId,
                             @Param("seq") Integer seq,
                             @Param("kind") String kind,
                             @Param("progressType") String progressType,
                             @Param("message") String message,
                             @Param("messageJson") String messageJson,
                             @Param("triggerInput") String triggerInput);

    /** Финальный ANSWER завершает ран: вся его переписка становится видимой истории. */
    @Modifying
    @Query("UPDATE ChannelSessionMessage m SET m.completed = true WHERE m.runId = :runId")
    int markRunCompleted(@Param("runId") UUID runId);

    List<ChannelSessionMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    /**
     * Хвост истории «как видел пользователь»: только завершённые раны, новые первыми
     * (вызывающий разворачивает). Порядок — uuidv7-PK (время + монотонность в ране).
     */
    List<ChannelSessionMessage> findBySessionIdAndCompletedTrueOrderByIdDesc(UUID sessionId, Pageable pageable);

    Optional<ChannelSessionMessage> findFirstBySessionIdAndTriggerInputIsNotNullOrderByCreatedAtDesc(
            UUID sessionId);

    /** Сессии агента, в которых были сообщения с момента {@code since} — для дневного сбора заметок. */
    @Query("""
            SELECT DISTINCT m.sessionId FROM ChannelSessionMessage m
            WHERE m.agentId = :agentId AND m.createdAt > :since
            """)
    List<UUID> findSessionIdsByAgentSince(@Param("agentId") UUID agentId, @Param("since") java.time.LocalDateTime since);
}
