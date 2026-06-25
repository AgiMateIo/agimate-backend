package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.AgentConnection;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentConnectionRepository extends JpaRepository<AgentConnection, UUID> {

    @Query("""
            SELECT ac FROM AgentConnection ac
            WHERE ac.agentId = :agentId AND ac.connectionId = :connectionId AND ac.deletedAt IS NULL
            """)
    Optional<AgentConnection> findActiveBinding(@Param("agentId") UUID agentId,
                                                @Param("connectionId") UUID connectionId);

    @Query("SELECT ac FROM AgentConnection ac WHERE ac.agentId = :agentId AND ac.deletedAt IS NULL")
    List<AgentConnection> findActiveByAgentId(@Param("agentId") UUID agentId);

    @Query("SELECT ac FROM AgentConnection ac WHERE ac.connectionId = :connectionId AND ac.deletedAt IS NULL")
    List<AgentConnection> findActiveByConnectionId(@Param("connectionId") UUID connectionId);

    boolean existsByAgentIdAndConnectionIdAndDeletedAtIsNull(UUID agentId, UUID connectionId);

    @Modifying
    @Query("UPDATE AgentConnection ac SET ac.deletedAt = :now WHERE ac.id = :id")
    void softDelete(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
