package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.AgentSession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentSessionRepository extends JpaRepository<AgentSession, UUID> {

    List<AgentSession> findByChannelIdOrderByLastActivityAtDesc(UUID channelId);

    List<AgentSession> findByChannelIdInOrderByLastActivityAtDesc(List<UUID> channelIds);

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

    /** Activity of a session that is not loaded: bulk update, so {@code updated_at} is stamped here. */
    @Modifying
    @Query("UPDATE AgentSession s SET s.lastActivityAt = :now, s.updatedAt = :now WHERE s.id = :id")
    void touch(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
