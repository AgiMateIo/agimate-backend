package ru.agimate.controlapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.ChannelSessionMessage;
import ru.agimate.controlapi.database.projections.SessionNoteLineProjection;

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

    /** A final ANSWER completes the run: its whole exchange becomes visible history. */
    @Modifying
    @Query("UPDATE ChannelSessionMessage m SET m.completed = true WHERE m.runId = :runId")
    int markRunCompleted(@Param("runId") UUID runId);

    List<ChannelSessionMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    /**
     * A session's history for the manage view. Rows without text are dropped by the query rather
     * than after the page is fetched: a progress row carries none, and filtering afterwards would
     * hand back a page shorter than the size that was asked for — with no way to tell that from
     * the end of the history.
     */
    @Query("""
            SELECT m FROM ChannelSessionMessage m
            WHERE m.sessionId = :sessionId
              AND m.message IS NOT NULL
            """)
    Page<ChannelSessionMessage> findWithMessageBySessionId(@Param("sessionId") UUID sessionId,
                                                           Pageable pageable);

    /**
     * The tail of history «as the user saw it»: completed runs only, newest first (the caller
     * reverses). Ordering is by the uuidv7 PK (time plus monotonicity within a run).
     */
    List<ChannelSessionMessage> findBySessionIdAndCompletedTrueOrderByIdDesc(UUID sessionId, Pageable pageable);

    Optional<ChannelSessionMessage> findFirstBySessionIdAndTriggerInputIsNotNullOrderByCreatedAtDesc(
            UUID sessionId);

    /**
     * Sessions of an agent with messages since {@code since} — for the daily note collection. A
     * subagent's session is left out: its other side is the agent itself, and what it learned is
     * already in the conversation it reported to.
     */
    @Query("""
            SELECT DISTINCT m.sessionId FROM ChannelSessionMessage m
            WHERE m.agentId = :agentId AND m.createdAt > :since
              AND NOT EXISTS (SELECT 1 FROM AgentSession s
                              WHERE s.id = m.sessionId AND s.parentSessionId IS NOT NULL)
            """)
    List<UUID> findSessionIdsByAgentSince(@Param("agentId") UUID agentId, @Param("since") java.time.LocalDateTime since);

    /**
     * The dialogue of one session for the daily note request — newest first, the caller reverses.
     * A connection's session lives as long as the connection, so the note takes the window and not
     * the session whole; {@code >} to agree with {@link #findSessionIdsByAgentSince}, or a session
     * selected there could come back empty here. PROGRESS is dropped in SQL: those rows are the
     * channel's own markup (💭, «🔧 name») and the bulk of a session. Ordering is by the uuidv7 key,
     * not {@code created_at}: rows of one run share a timestamp.
     */
    @Query("""
            SELECT m.kind AS kind, m.message AS message
            FROM ChannelSessionMessage m
            WHERE m.sessionId = :sessionId
              AND m.createdAt > :since
              AND m.kind <> ru.agimate.controlapi.database.enums.ChannelSessionMessageKind.PROGRESS
            ORDER BY m.id DESC
            """)
    List<SessionNoteLineProjection> findNoteLinesBySessionSince(@Param("sessionId") UUID sessionId,
                                                                @Param("since") java.time.LocalDateTime since,
                                                                Pageable pageable);
}
