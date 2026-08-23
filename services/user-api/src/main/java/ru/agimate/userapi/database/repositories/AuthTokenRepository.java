package ru.agimate.userapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.userapi.database.entities.AuthToken;
import ru.agimate.userapi.database.entities.AuthTokenPurpose;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthTokenRepository extends JpaRepository<AuthToken, UUID> {

    Optional<AuthToken> findByTokenHash(String tokenHash);

    /**
     * Spends the token as the condition of the write rather than after a read: two people following
     * the same link at the same time must set the password once, not twice.
     *
     * @return 1 if this caller spent it, 0 if it was already spent or superseded
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AuthToken t
            SET t.usedAt = :now, t.updatedAt = :now
            WHERE t.tokenHash = :tokenHash AND t.usedAt IS NULL
            """)
    int claim(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    /**
     * Retires every live token of one purpose before a new one is issued, so that the latest letter
     * is the one that works and the older ones in the mailbox stop being keys.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AuthToken t
            SET t.usedAt = :now, t.updatedAt = :now
            WHERE t.userId = :userId AND t.purpose = :purpose AND t.usedAt IS NULL
            """)
    int retireLive(@Param("userId") UUID userId,
                   @Param("purpose") AuthTokenPurpose purpose,
                   @Param("now") LocalDateTime now);

    /**
     * How many letters of this purpose this person has been sent since a moment — the throttle, kept
     * in the database rather than in memory because a counter a restart resets is not a throttle.
     */
    @Query("""
            SELECT COUNT(t) FROM AuthToken t
            WHERE t.userId = :userId AND t.purpose = :purpose AND t.createdAt > :since
            """)
    long countIssuedSince(@Param("userId") UUID userId,
                          @Param("purpose") AuthTokenPurpose purpose,
                          @Param("since") LocalDateTime since);

    /**
     * Spent and expired tokens alike are dropped well after they died: while the row exists, a
     * second attempt is answered "already used", and once it is gone the same attempt is
     * indistinguishable from a typo.
     */
    @Modifying
    @Query("DELETE FROM AuthToken t WHERE t.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
