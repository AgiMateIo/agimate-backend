package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.ChannelSession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ChannelSessionRepository extends JpaRepository<ChannelSession, UUID> {

    List<ChannelSession> findByChannelIdOrderByLastMessageAtDesc(UUID channelId);

    List<ChannelSession> findByChannelIdInOrderByLastMessageAtDesc(List<UUID> channelIds);

    @Query("""
            SELECT s FROM ChannelSession s
            WHERE s.channelId = :channelId
              AND s.closedAt IS NULL
              AND s.lastMessageAt > :threshold
            ORDER BY s.lastMessageAt DESC
            """)
    List<ChannelSession> findActive(
            @Param("channelId") UUID channelId,
            @Param("threshold") LocalDateTime threshold
    );
}
