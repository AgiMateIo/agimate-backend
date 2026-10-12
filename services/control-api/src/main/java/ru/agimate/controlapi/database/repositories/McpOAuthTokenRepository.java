package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.McpOAuthToken;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface McpOAuthTokenRepository extends JpaRepository<McpOAuthToken, UUID> {

    Optional<McpOAuthToken> findByKeyId(String keyId);

    /**
     * Marks a token replaced, once. A plain {@code @Modifying}: the claim sits in the middle of a
     * transaction that still reads lazily afterwards. 0 means someone else got there first.
     */
    @Modifying
    @Query("""
            UPDATE McpOAuthToken t SET t.supersededAt = :now, t.updatedAt = :now
            WHERE t.id = :id AND t.supersededAt IS NULL
            """)
    int supersede(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /**
     * Spends the single retry a rotated refresh token is allowed: moves its rotation mark out of the
     * window, so the next presentation reads as a replay. 0 means the retry was already spent, or the
     * window had passed.
     */
    @Modifying
    @Query("""
            UPDATE McpOAuthToken t SET t.supersededAt = :closedAt, t.updatedAt = :now
            WHERE t.id = :id AND t.supersededAt >= :windowStart
            """)
    int spendRetry(@Param("id") UUID id, @Param("windowStart") LocalDateTime windowStart,
                   @Param("closedAt") LocalDateTime closedAt, @Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM McpOAuthToken t WHERE t.grantId = :grantId")
    int deleteByGrantId(@Param("grantId") UUID grantId);

    @Modifying
    @Query("""
            DELETE FROM McpOAuthToken t
            WHERE t.grantId IN (SELECT g.id FROM McpOAuthGrant g WHERE g.agentId = :agentId)
            """)
    int deleteByAgentId(@Param("agentId") UUID agentId);

    @Modifying
    @Query("DELETE FROM McpOAuthToken t WHERE t.expiresAt < :threshold")
    int deleteExpiredBefore(@Param("threshold") LocalDateTime threshold);
}
