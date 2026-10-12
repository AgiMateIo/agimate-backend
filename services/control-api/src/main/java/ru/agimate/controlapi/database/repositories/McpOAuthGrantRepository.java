package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.McpOAuthGrant;
import ru.agimate.controlapi.database.enums.McpOAuthGrantStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface McpOAuthGrantRepository extends JpaRepository<McpOAuthGrant, UUID> {

    List<McpOAuthGrant> findByAgentIdAndStatusOrderByCreatedAtDesc(UUID agentId, McpOAuthGrantStatus status);

    /** Moves PENDING to ACTIVE once; 0 means the grant is no longer pending. */
    @Modifying
    @Query("""
            UPDATE McpOAuthGrant g SET g.status = ru.agimate.controlapi.database.enums.McpOAuthGrantStatus.ACTIVE,
                g.lastUsedAt = :now, g.updatedAt = :now
            WHERE g.id = :id AND g.status = ru.agimate.controlapi.database.enums.McpOAuthGrantStatus.PENDING
            """)
    int activate(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Modifying
    @Query("""
            UPDATE McpOAuthGrant g SET g.status = ru.agimate.controlapi.database.enums.McpOAuthGrantStatus.REVOKED,
                g.revokedAt = :now, g.updatedAt = :now
            WHERE g.id = :id AND g.status <> ru.agimate.controlapi.database.enums.McpOAuthGrantStatus.REVOKED
            """)
    int revoke(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Modifying
    @Query("""
            UPDATE McpOAuthGrant g SET g.status = ru.agimate.controlapi.database.enums.McpOAuthGrantStatus.REVOKED,
                g.revokedAt = :now, g.updatedAt = :now
            WHERE g.agentId = :agentId AND g.status <> ru.agimate.controlapi.database.enums.McpOAuthGrantStatus.REVOKED
            """)
    int revokeByAgentId(@Param("agentId") UUID agentId, @Param("now") LocalDateTime now);

    /** Written at most once a minute per grant: otherwise every tool call becomes a write. */
    @Modifying
    @Query("""
            UPDATE McpOAuthGrant g SET g.lastUsedAt = :now, g.updatedAt = :now
            WHERE g.id = :id AND (g.lastUsedAt IS NULL OR g.lastUsedAt < :threshold)
            """)
    int touch(@Param("id") UUID id, @Param("now") LocalDateTime now, @Param("threshold") LocalDateTime threshold);

    @Modifying
    @Query("""
            DELETE FROM McpOAuthGrant g
            WHERE g.status = ru.agimate.controlapi.database.enums.McpOAuthGrantStatus.PENDING
              AND g.createdAt < :threshold
            """)
    int deletePendingBefore(@Param("threshold") LocalDateTime threshold);

    /** An active grant whose every token has expired and been swept can no longer be used by anyone. */
    @Modifying
    @Query("""
            UPDATE McpOAuthGrant g SET g.status = ru.agimate.controlapi.database.enums.McpOAuthGrantStatus.REVOKED,
                g.revokedAt = :now, g.updatedAt = :now
            WHERE g.status = ru.agimate.controlapi.database.enums.McpOAuthGrantStatus.ACTIVE
              AND NOT EXISTS (SELECT 1 FROM McpOAuthToken t WHERE t.grantId = g.id)
            """)
    int revokeExhausted(@Param("now") LocalDateTime now);
}
