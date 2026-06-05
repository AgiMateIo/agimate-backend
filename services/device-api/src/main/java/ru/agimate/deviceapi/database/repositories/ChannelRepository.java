package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.Channel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChannelRepository extends JpaRepository<Channel, UUID> {

    Optional<Channel> findByIdAndDeletedAtIsNull(UUID id);

    List<Channel> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID userId);

    List<Channel> findByAgentIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID agentId);

    List<Channel> findByUserIdAndAgentIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID userId, UUID agentId);

    @Query("""
            SELECT c FROM Channel c
            WHERE c.userId = :userId
              AND c.agentId = :agentId
              AND c.triggerConnectorCode = :connectorCode
              AND c.triggerIdentity = :identity
              AND c.triggerName = :triggerName
              AND c.deletedAt IS NULL
            """)
    Optional<Channel> findActiveByTriggerKey(
            @Param("userId") UUID userId,
            @Param("agentId") UUID agentId,
            @Param("connectorCode") String connectorCode,
            @Param("identity") String identity,
            @Param("triggerName") String triggerName
    );
}
