package ru.agimate.userapi.database.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Somebody who has asked for an account and has not yet shown that the address is theirs. It becomes
 * a {@link UserEntity} at the moment the letter is opened and not a second earlier — an unconfirmed
 * row in {@code users} could be claimed by whoever registered with somebody else's address.
 *
 * <p>No password here on purpose: it is chosen by whoever opens the letter. Carrying one from the
 * form would mean the password is named by one person and the mailbox proved by another, and an
 * unsolicited confirmation click would hand the clicker an account somebody else knows the way into.
 */
@Entity
@Table(name = "pending_registrations", uniqueConstraints = {
        @UniqueConstraint(name = "uq_pending_registrations_token_hash", columnNames = "token_hash")
})
@Getter
@Setter
public class PendingRegistration extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Not unique: two attempts from one address are ordinary, and the confirmed one wins. */
    @Column(name = "email", nullable = false, columnDefinition = "TEXT")
    private String email;

    @Column(name = "display_name", columnDefinition = "TEXT")
    private String displayName;

    @Column(name = "referred_by")
    private UUID referredBy;

    @Column(name = "token_hash", nullable = false, columnDefinition = "TEXT")
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Set when the letter is opened, and also when a newer request for the same address supersedes it. */
    @Column(name = "used_at")
    private LocalDateTime usedAt;
}
