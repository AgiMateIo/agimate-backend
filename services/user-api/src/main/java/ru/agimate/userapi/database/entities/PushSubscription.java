package ru.agimate.userapi.database.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One device's standing request to be notified (docs/decisions/push-notifications.md). Not a device
 * registry: nothing here identifies a phone, a reinstall simply produces a new token and a new row.
 *
 * <p>Identity is the token, hence {@code uq_push_subscriptions_token} — a token relogged under
 * another account moves to that account rather than existing twice, or the previous owner would keep
 * receiving notifications on someone else's phone.
 */
@Entity
@Table(name = "push_subscriptions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_push_subscriptions_token", columnNames = "token")
})
@Getter
@Setter
public class PushSubscription extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * The sign-in that registered it, from the {@code asid} claim. Null when the access token
     * predates the claim — such a row waits for the {@code last_seen_at} sweep instead of going with
     * its session.
     */
    @Column(name = "auth_session_id")
    private UUID authSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, columnDefinition = "TEXT")
    private PushProvider provider;

    @Column(name = "token", nullable = false, columnDefinition = "TEXT")
    private String token;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;
}
