package ru.agimate.userapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.userapi.database.entities.AuthSession;
import ru.agimate.userapi.database.entities.SessionRevokeReason;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    /**
     * A refresh arrives with one id and no clue which generation it belongs to, so both columns are
     * searched at once. Which one matched is what the caller decides on afterwards: the current id
     * rotates, the previous one is a retry or a theft depending on how long ago it was replaced.
     */
    @Query("""
            SELECT s FROM AuthSession s
            WHERE s.currentJti = :jti OR s.previousJti = :jti
            """)
    Optional<AuthSession> findByJti(@Param("jti") String jti);

    /**
     * Rotation as a single conditional write, so that two refreshes racing on the same id cannot
     * both mint a pair: the second one matches nothing and is told to retry, rather than quietly
     * producing a token the client will never store.
     *
     * @param expectedJti the id the caller read; the update is abandoned if it has moved on since
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AuthSession s
            SET s.previousJti = s.currentJti,
                s.currentJti = :newJti,
                s.rotatedAt = :now,
                s.lastSeenAt = :now,
                s.expiresAt = :expiresAt,
                s.updatedAt = :now
            WHERE s.id = :id
              AND s.currentJti = :expectedJti
              AND s.revokedAt IS NULL
            """)
    int rotate(@Param("id") UUID id,
               @Param("expectedJti") String expectedJti,
               @Param("newJti") String newJti,
               @Param("now") LocalDateTime now,
               @Param("expiresAt") LocalDateTime expiresAt);

    /**
     * Keeps the session alive without moving it to a new generation — the answer to a refresh whose
     * response was lost, which is re-served the pair it should have received rather than pushed
     * forward again. Conditional for the same reason as {@link #rotate}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AuthSession s
            SET s.lastSeenAt = :now,
                s.expiresAt = :expiresAt,
                s.updatedAt = :now
            WHERE s.id = :id
              AND s.currentJti = :expectedJti
              AND s.revokedAt IS NULL
            """)
    int touch(@Param("id") UUID id,
              @Param("expectedJti") String expectedJti,
              @Param("now") LocalDateTime now,
              @Param("expiresAt") LocalDateTime expiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AuthSession s
            SET s.revokedAt = :now, s.revokeReason = :reason, s.updatedAt = :now
            WHERE s.id = :id AND s.revokedAt IS NULL
            """)
    int revoke(@Param("id") UUID id,
               @Param("reason") SessionRevokeReason reason,
               @Param("now") LocalDateTime now);

    @Query("""
            SELECT s FROM AuthSession s
            WHERE s.userId = :userId
              AND s.revokedAt IS NULL
              AND s.expiresAt > :now
            ORDER BY s.lastSeenAt DESC
            """)
    List<AuthSession> findActive(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    Optional<AuthSession> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Revoked rows are kept until they expire on their own: until then, a refresh that arrives with
     * a revoked id should be told it was revoked rather than that no such session ever existed.
     */
    @Modifying
    @Query("DELETE FROM AuthSession s WHERE s.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
