package ru.agimate.userapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.userapi.database.entities.PushSubscription;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {

    /**
     * Registration: the same token registered again is the same subscription, whoever it now belongs
     * to. The owner is overwritten rather than compared — a device signed into another account must
     * move, and a second row would keep notifying the previous owner.
     * The {@code id} primary key is populated by the database default ({@code uuidv7()}).
     */
    @Modifying
    @Query(value = """
            INSERT INTO push_subscriptions
                (user_id, auth_session_id, provider, token, last_seen_at, created_at, updated_at)
            VALUES
                (:userId, :authSessionId, :provider, :token, :now, :now, :now)
            ON CONFLICT (token) DO UPDATE SET
                user_id = EXCLUDED.user_id,
                auth_session_id = EXCLUDED.auth_session_id,
                provider = EXCLUDED.provider,
                last_seen_at = EXCLUDED.last_seen_at,
                updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    void upsert(@Param("userId") UUID userId,
                @Param("authSessionId") UUID authSessionId,
                @Param("provider") String provider,
                @Param("token") String token,
                @Param("now") LocalDateTime now);

    /** The fan-out of a notification; a person has several devices. */
    List<PushSubscription> findByUserId(UUID userId);

    /** Signing out. Scoped to the owner: a token is unique, but deleting someone else's is not ours to do. */
    @Modifying
    @Query("DELETE FROM PushSubscription s WHERE s.userId = :userId AND s.token = :token")
    int deleteByUserIdAndToken(@Param("userId") UUID userId, @Param("token") String token);

    /** The transport said the token is gone — the app was uninstalled or reinstalled. */
    @Modifying
    @Query("DELETE FROM PushSubscription s WHERE s.token = :token")
    int deleteByToken(@Param("token") String token);

    /**
     * Revocation of a sign-in takes its subscriptions with it. An explicit delete rather than the
     * foreign key's cascade: revoking stamps {@code revoked_at} and keeps the row, so the cascade
     * only fires later, when the sweep deletes the expired session.
     */
    @Modifying
    @Query("DELETE FROM PushSubscription s WHERE s.authSessionId = :authSessionId")
    int deleteByAuthSessionId(@Param("authSessionId") UUID authSessionId);

    /** The backstop for subscriptions with no session behind them; the rest go by the cascade. */
    @Modifying
    @Query("DELETE FROM PushSubscription s WHERE s.lastSeenAt < :threshold")
    int deleteByLastSeenAtBefore(@Param("threshold") LocalDateTime threshold);
}
