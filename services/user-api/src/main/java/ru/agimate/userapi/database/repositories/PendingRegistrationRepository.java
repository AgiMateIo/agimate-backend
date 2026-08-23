package ru.agimate.userapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.userapi.database.entities.PendingRegistration;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PendingRegistrationRepository extends JpaRepository<PendingRegistration, UUID> {

    Optional<PendingRegistration> findByTokenHash(String tokenHash);

    /**
     * The live request for this address, if there is one — what a "send it again" has to resend and
     * what a repeated registration replaces.
     */
    @Query("""
            SELECT r FROM PendingRegistration r
            WHERE r.email = :email AND r.usedAt IS NULL AND r.expiresAt > :now
            ORDER BY r.createdAt DESC
            LIMIT 1
            """)
    Optional<PendingRegistration> findLive(@Param("email") String email, @Param("now") LocalDateTime now);

    /**
     * Confirms the request as the condition of the write: two clicks on one letter must create one
     * account, not two.
     *
     * @return 1 if this caller confirmed it, 0 if it was already used or superseded
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PendingRegistration r
            SET r.usedAt = :now, r.updatedAt = :now
            WHERE r.tokenHash = :tokenHash AND r.usedAt IS NULL
            """)
    int claim(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    /** Retires whatever was live for this address, so that only the newest letter still works. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PendingRegistration r
            SET r.usedAt = :now, r.updatedAt = :now
            WHERE r.email = :email AND r.usedAt IS NULL
            """)
    int retireLive(@Param("email") String email, @Param("now") LocalDateTime now);

    /**
     * How many letters this address has been sent since a moment. Counted by address rather than by
     * person because there is no person yet — which is exactly why this throttle matters: without it
     * anyone's mailbox can be filled from an endpoint that needs no account at all.
     */
    @Query("""
            SELECT COUNT(r) FROM PendingRegistration r
            WHERE r.email = :email AND r.createdAt > :since
            """)
    long countIssuedSince(@Param("email") String email, @Param("since") LocalDateTime since);

    @Modifying
    @Query("DELETE FROM PendingRegistration r WHERE r.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
