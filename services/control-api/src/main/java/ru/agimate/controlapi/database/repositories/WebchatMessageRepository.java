package ru.agimate.controlapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.WebchatMessage;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebchatMessageRepository extends JpaRepository<WebchatMessage, UUID> {

    /**
     * Idempotent insert: skip on a duplicate (session_id, message_id) instead of failing —
     * absorbs DBOS-replay / retry of the same outbound message without poisoning the transaction.
     * The {@code id} primary key is populated by the database default ({@code uuidv7()}).
     * Returns 1 if inserted, 0 if the row already existed.
     */
    @Modifying
    @Query(value = """
            INSERT INTO webchat_messages
                (user_id, agent_id, channel_id, session_id, direction, stream, message_id, text,
                 parts, created_at, updated_at)
            VALUES
                (:userId, :agentId, :channelId, :sessionId, :direction, :stream, :messageId, :text,
                 CAST(:partsJson AS jsonb), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (session_id, message_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreConflict(@Param("userId") UUID userId,
                             @Param("agentId") UUID agentId,
                             @Param("channelId") UUID channelId,
                             @Param("sessionId") UUID sessionId,
                             @Param("direction") String direction,
                             @Param("stream") String stream,
                             @Param("messageId") String messageId,
                             @Param("text") String text,
                             @Param("partsJson") String partsJson);

    Page<WebchatMessage> findBySessionId(UUID sessionId, Pageable pageable);

    boolean existsByIdAndSessionId(UUID id, UUID sessionId);

    /**
     * Unread counts for a page of sessions: what the agent said past each session's read pointer.
     * {@code progress} lines are excluded — one answer walks through a dozen of them, and a badge
     * counting those would say «12» about a single reply.
     *
     * @return rows of {@code (session_id, count)}; a session with nothing unread is simply absent
     */
    @Query(value = """
            SELECT m.session_id, COUNT(*)
            FROM webchat_messages m
            JOIN agent_sessions s ON s.id = m.session_id
            WHERE m.session_id IN (:sessionIds)
              AND m.direction = 'AGENT'
              AND m.stream IS DISTINCT FROM 'progress'
              AND (s.last_read_message_id IS NULL OR m.id > s.last_read_message_id)
            GROUP BY m.session_id
            """, nativeQuery = true)
    List<Object[]> countUnreadBySessionIds(@Param("sessionIds") Collection<UUID> sessionIds);

    /** The same count folded per agent — the badge of a contact row, over all its conversations. */
    @Query(value = """
            SELECT m.agent_id, COUNT(*)
            FROM webchat_messages m
            JOIN agent_sessions s ON s.id = m.session_id
            WHERE m.agent_id IN (:agentIds)
              AND m.direction = 'AGENT'
              AND m.stream IS DISTINCT FROM 'progress'
              AND (s.last_read_message_id IS NULL OR m.id > s.last_read_message_id)
            GROUP BY m.agent_id
            """, nativeQuery = true)
    List<Object[]> countUnreadByAgentIds(@Param("agentIds") Collection<UUID> agentIds);

    /**
     * The last message shown in each session — the preview of a listing row. Ordered by {@code id}
     * rather than {@code created_at}: it is a uuidv7, so it carries the same order and breaks the
     * tie of two messages sharing a timestamp.
     *
     * @return rows of {@code (session_id, direction, text, has_attachments, created_at)}
     */
    @Query(value = """
            SELECT DISTINCT ON (m.session_id)
                   m.session_id, m.direction, m.text, (m.parts IS NOT NULL), m.created_at
            FROM webchat_messages m
            WHERE m.session_id IN (:sessionIds)
              AND m.stream IS DISTINCT FROM 'progress'
            ORDER BY m.session_id, m.id DESC
            """, nativeQuery = true)
    List<Object[]> findLastMessagesBySessionIds(@Param("sessionIds") Collection<UUID> sessionIds);

    /**
     * The last message of each agent's newest conversation — the preview of a contact row. Carries
     * {@code session_id} too: tapping a contact opens exactly the conversation this preview is from.
     *
     * @return rows of {@code (agent_id, session_id, direction, text, has_attachments, created_at)}
     */
    @Query(value = """
            SELECT DISTINCT ON (m.agent_id)
                   m.agent_id, m.session_id, m.direction, m.text, (m.parts IS NOT NULL), m.created_at
            FROM webchat_messages m
            WHERE m.agent_id IN (:agentIds)
              AND m.stream IS DISTINCT FROM 'progress'
            ORDER BY m.agent_id, m.id DESC
            """, nativeQuery = true)
    List<Object[]> findLastMessagesByAgentIds(@Param("agentIds") Collection<UUID> agentIds);

    /** The newest shown message of a session — where «read everything» puts the pointer. */
    @Query(value = """
            SELECT m.id FROM webchat_messages m
            WHERE m.session_id = :sessionId
              AND m.stream IS DISTINCT FROM 'progress'
            ORDER BY m.id DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<UUID> findLastMessageId(@Param("sessionId") UUID sessionId);
}
