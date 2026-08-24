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
 * A provider identity established by a round trip that was asked for as a binding, waiting for the
 * account holder to claim it.
 *
 * <p>It names no account, and that is the whole design: the account is named afterwards, by an
 * authenticated request carrying an access token in a header. Whoever completes the round trip
 * therefore decides nothing — which is what a ticket issued beforehand got wrong, because a round
 * trip is started by following a link and can be completed in a browser that is not its owner's.
 */
@Entity
@Table(name = "provider_link_proofs", uniqueConstraints = {
        @UniqueConstraint(name = "uq_provider_link_proofs_proof_hash", columnNames = "proof_hash")
})
@Getter
@Setter
public class ProviderLinkProof extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** SHA-256 of the proof. The proof itself exists in the redirect and in the page that got it. */
    @Column(name = "proof_hash", nullable = false, columnDefinition = "TEXT")
    private String proofHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "oauth_provider", nullable = false, columnDefinition = "TEXT")
    private OAuthProviderType oauthProvider;

    @Column(name = "provider_user_id", nullable = false, columnDefinition = "TEXT")
    private String providerUserId;

    /** May be null: this flow never consults the address, and a provider is free to give none. */
    @Column(name = "email", columnDefinition = "TEXT")
    private String email;

    @Column(name = "first_name", columnDefinition = "TEXT")
    private String firstName;

    @Column(name = "last_name", columnDefinition = "TEXT")
    private String lastName;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;
}
