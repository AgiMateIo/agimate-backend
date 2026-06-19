package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.Channel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChannelRepository extends JpaRepository<Channel, UUID> {

    Optional<Channel> findByIdAndDeletedAtIsNull(UUID id);

    List<Channel> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID userId);

    List<Channel> findByAgentIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID agentId);

    List<Channel> findByUserIdAndAgentIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID userId, UUID agentId);

    /**
     * Активный канал для (agent, connector, identity) — источник маршрута триггера. Единственность
     * гарантирует частичный уникальный индекс {@code WHERE deleted_at IS NULL}.
     */
    Optional<Channel> findByAgentIdAndConnectorCodeAndIdentityAndDeletedAtIsNull(
            UUID agentId, String connectorCode, String identity);
}
