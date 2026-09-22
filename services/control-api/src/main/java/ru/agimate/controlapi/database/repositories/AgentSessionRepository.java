package ru.agimate.controlapi.database.repositories;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.projections.AgentRequestProjection;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentSessionRepository
        extends JpaRepository<AgentSession, UUID>, JpaSpecificationExecutor<AgentSession> {

    /**
     * Live sessions of a channel, freshest first. The scope is spelled out rather than implied by
     * {@code channelId}: the table holds other kinds now, and a query should say what it means.
     */
    @Query("""
            SELECT s FROM AgentSession s
            WHERE s.scope = ru.agimate.controlapi.database.enums.AgentSessionScope.CHANNEL
              AND s.channelId = :channelId
              AND s.closedAt IS NULL
              AND s.lastActivityAt > :threshold
            ORDER BY s.lastActivityAt DESC
            """)
    List<AgentSession> findActive(
            @Param("channelId") UUID channelId,
            @Param("threshold") LocalDateTime threshold
    );

    /** The live session of a connection; at most one exists — {@code uq_agent_sessions_agent_id_connection_id_live}. */
    @Query("""
            SELECT s FROM AgentSession s
            WHERE s.scope = ru.agimate.controlapi.database.enums.AgentSessionScope.CONNECTION
              AND s.agentId = :agentId
              AND s.connectionId = :connectionId
              AND s.closedAt IS NULL
            """)
    Optional<AgentSession> findLiveConnectionSession(@Param("agentId") UUID agentId,
                                                     @Param("connectionId") UUID connectionId);

    /**
     * Conflict-tolerant creation: a storm of triggers for one connection resolves the session
     * concurrently, and the loser must read the winner's row rather than raise — an exception here
     * would poison the routing of the remaining recipients.
     *
     * @return 1 when this call created the row, 0 when someone else already had
     */
    @Modifying
    @Query(value = """
            INSERT INTO agent_sessions
                (scope, agent_id, user_id, connector_code, connection_id, last_activity_at, created_at, updated_at)
            VALUES ('CONNECTION', :agentId, :userId, :connectorCode, :connectionId, :now, :now, :now)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertConnectionSession(@Param("agentId") UUID agentId,
                                @Param("userId") UUID userId,
                                @Param("connectorCode") String connectorCode,
                                @Param("connectionId") UUID connectionId,
                                @Param("now") LocalDateTime now);

    /**
     * The session row under a write lock — the serialisation point for starting subagents of one
     * conversation: calls of one turn run in parallel, and the cap must count what the others created.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AgentSession s WHERE s.id = :id")
    Optional<AgentSession> lockById(@Param("id") UUID id);

    /**
     * Subagents of a conversation that still owe a report: a live run that is neither absorbed nor
     * stopped, a detached tool call still to be delivered (its result wakes the subagent again), or —
     * for a session created a moment ago — no run yet, since the run is routed after
     * the session's transaction commits. Both liveness conditions share the stale-run window, past
     * which nothing is believed alive: a queue that stalled, or a request that never became a run,
     * must not hold the conversation's count up forever.
     */
    @Query(value = """
            SELECT COUNT(*) FROM agent_sessions s
            WHERE s.parent_session_id = :parentSessionId
              AND (EXISTS (SELECT 1 FROM agent_runs r
                           WHERE r.session_id = s.id
                             AND r.steered_at IS NULL
                             AND r.cancel_requested_at IS NULL
                             AND (r.status = 'RUNNING'
                                  OR (r.status = 'ENQUEUED' AND r.created_at > :liveSince)))
                   OR EXISTS (SELECT 1 FROM tool_call_logs t
                              JOIN agent_runs r ON r.id = t.run_id
                              WHERE r.session_id = s.id
                                AND r.cancel_requested_at IS NULL
                                AND t.detached_at IS NOT NULL
                                AND t.delivered_at IS NULL
                                AND t.created_at > :liveSince)
                   OR (s.created_at > :liveSince
                       AND NOT EXISTS (SELECT 1 FROM agent_runs r WHERE r.session_id = s.id)))
            """, nativeQuery = true)
    long countWorkingChildren(@Param("parentSessionId") UUID parentSessionId,
                              @Param("liveSince") LocalDateTime liveSince);

    /** Subagent sessions of a conversation, most recently active first. */
    @Query("""
            SELECT s FROM AgentSession s
            WHERE s.parentSessionId = :parentSessionId
            ORDER BY s.lastActivityAt DESC, s.id DESC
            """)
    List<AgentSession> findChildren(@Param("parentSessionId") UUID parentSessionId, Pageable pageable);

    /**
     * Requests handed to the agents of a team: threads of the {@code agents} connector whose callee
     * is in the team, freshest activity first. The conversation the thread works for is joined for
     * both the asker ({@code p.agentId} — a thread carries only its callee) and the place the answer
     * went back to.
     *
     * <p>Every filter but {@code userId} is optional; {@code teamId} is null only for the event
     * payload of a single {@code threadId}, where the team is what the row reports rather than what
     * it is narrowed by. A deleted agent is not filtered out: its past requests are still history.
     */
    @Query(value = """
            SELECT s.id AS id, s.userId AS userId, a.agenticTeamId AS teamId, s.title AS title,
                   s.agentId AS toAgentId, p.agentId AS fromAgentId,
                   p.id AS originSessionId, p.title AS originTitle,
                   p.connectorCode AS originConnectorCode,
                   s.createdAt AS createdAt, s.lastActivityAt AS lastActivityAt,
                   s.closedAt AS closedAt
            FROM AgentSession s, AgentSession p, Agent a
            WHERE s.connectorCode = :connectorCode
              AND s.userId = :userId
              AND p.id = s.parentSessionId
              AND a.id = s.agentId
              AND (:teamId IS NULL OR a.agenticTeamId = :teamId)
              AND (:threadId IS NULL OR s.id = :threadId)
              AND (:toAgentId IS NULL OR s.agentId = :toAgentId)
              AND (:fromAgentId IS NULL OR p.agentId = :fromAgentId)
              AND (:agentId IS NULL OR s.agentId = :agentId OR p.agentId = :agentId)
              AND (CAST(:since AS LocalDateTime) IS NULL OR s.lastActivityAt >= :since)
            ORDER BY s.lastActivityAt DESC, s.id DESC
            """,
            countQuery = """
            SELECT COUNT(s) FROM AgentSession s, AgentSession p, Agent a
            WHERE s.connectorCode = :connectorCode
              AND s.userId = :userId
              AND p.id = s.parentSessionId
              AND a.id = s.agentId
              AND (:teamId IS NULL OR a.agenticTeamId = :teamId)
              AND (:threadId IS NULL OR s.id = :threadId)
              AND (:toAgentId IS NULL OR s.agentId = :toAgentId)
              AND (:fromAgentId IS NULL OR p.agentId = :fromAgentId)
              AND (:agentId IS NULL OR s.agentId = :agentId OR p.agentId = :agentId)
              AND (CAST(:since AS LocalDateTime) IS NULL OR s.lastActivityAt >= :since)
            """)
    Page<AgentRequestProjection> findRequests(@Param("connectorCode") String connectorCode,
                                              @Param("userId") UUID userId,
                                              @Param("teamId") UUID teamId,
                                              @Param("threadId") UUID threadId,
                                              @Param("agentId") UUID agentId,
                                              @Param("fromAgentId") UUID fromAgentId,
                                              @Param("toAgentId") UUID toAgentId,
                                              @Param("since") LocalDateTime since,
                                              Pageable pageable);

    /** Activity of a session that is not loaded: bulk update, so {@code updated_at} is stamped here. */
    @Modifying
    @Query("UPDATE AgentSession s SET s.lastActivityAt = :now, s.updatedAt = :now WHERE s.id = :id")
    void touch(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /**
     * The read pointer moves forward only: a second device that opened an older view of the same
     * conversation must not un-read what the first one has already read.
     *
     * <p>Native because the comparison must be PostgreSQL's — its uuid ordering is bytewise, so a
     * uuidv7 sorts by time, while Java's {@code UUID.compareTo} treats the halves as signed longs
     * and would order the same values differently.
     *
     * @return 1 when the pointer moved, 0 when the session already stood at or past {@code messageId}
     */
    @Modifying
    @Query(value = """
            UPDATE agent_sessions
            SET last_read_message_id = :messageId, updated_at = :now
            WHERE id = :id
              AND (last_read_message_id IS NULL OR last_read_message_id < :messageId)
            """, nativeQuery = true)
    int advanceReadPointer(@Param("id") UUID id,
                           @Param("messageId") UUID messageId,
                           @Param("now") LocalDateTime now);

    /**
     * The agent's other conversations with a person that are alive right now — the {@code sessions}
     * block. Children (subagents, requests from another agent) are out: the conversation that owns
     * them lists them itself.
     */
    @Query("""
            SELECT s FROM AgentSession s
            WHERE s.agentId = :agentId
              AND s.id <> :excludeId
              AND s.scope = ru.agimate.controlapi.database.enums.AgentSessionScope.CHANNEL
              AND s.parentSessionId IS NULL
              AND s.closedAt IS NULL
              AND s.lastActivityAt > :since
            ORDER BY s.lastActivityAt DESC, s.id DESC
            """)
    List<AgentSession> findLiveSiblings(@Param("agentId") UUID agentId,
                                        @Param("excludeId") UUID excludeId,
                                        @Param("since") LocalDateTime since,
                                        Pageable pageable);

    /** A title from the compaction job; a rename by the user is never written over. */
    @Modifying
    @Query("""
            UPDATE AgentSession s
            SET s.title = :title,
                s.titleSource = ru.agimate.controlapi.database.enums.SessionTitleSource.GENERATED,
                s.updatedAt = :now
            WHERE s.id = :id
              AND (s.titleSource IS NULL
                   OR s.titleSource <> ru.agimate.controlapi.database.enums.SessionTitleSource.USER)
            """)
    int writeGeneratedTitle(@Param("id") UUID id, @Param("title") String title, @Param("now") LocalDateTime now);
}
