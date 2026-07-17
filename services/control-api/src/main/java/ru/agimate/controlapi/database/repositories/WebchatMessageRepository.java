package ru.agimate.controlapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.WebchatMessage;

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
}
