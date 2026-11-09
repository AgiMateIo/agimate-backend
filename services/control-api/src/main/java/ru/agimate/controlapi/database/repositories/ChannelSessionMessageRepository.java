package ru.agimate.controlapi.database.repositories;

import org.springframework.data.domain.Page;
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
                 trigger_input, tool_call_ids, created_at, updated_at)
            VALUES
                (:sessionId, :agentId, :runId, :seq, :kind, :progressType, :message,
                 CAST(:triggerInput AS jsonb), CAST(:toolCallIdsJson AS jsonb), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (run_id, seq) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreConflict(@Param("sessionId") UUID sessionId,
                             @Param("agentId") UUID agentId,
                             @Param("runId") UUID runId,
                             @Param("seq") Integer seq,
                             @Param("kind") String kind,
                             @Param("progressType") String progressType,
                             @Param("message") String message,
                             @Param("triggerInput") String triggerInput,
                             @Param("toolCallIdsJson") String toolCallIdsJson);

    List<ChannelSessionMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    /**
     * A session's history for the manage view. Rows without text are dropped by the query rather
     * than after the page is fetched: they carry nothing to show (an empty TOOL_RESULT line), and
     * filtering afterwards would hand back a page shorter than the size that was asked for — with
     * no way to tell that from the end of the history.
     */
    @Query("""
            SELECT m FROM ChannelSessionMessage m
            WHERE m.sessionId = :sessionId
              AND m.message IS NOT NULL
              AND m.message <> ''
            """)
    Page<ChannelSessionMessage> findWithMessageBySessionId(@Param("sessionId") UUID sessionId,
                                                           Pageable pageable);

    Optional<ChannelSessionMessage> findFirstBySessionIdAndTriggerInputIsNotNullOrderByCreatedAtDesc(
            UUID sessionId);
}
