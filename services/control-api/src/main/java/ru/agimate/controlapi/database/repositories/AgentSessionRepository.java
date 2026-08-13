package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.AgentSession;

import java.time.LocalDateTime;
import java.util.List;
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
}
