package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An encrypted secret (envelope encryption). The platform's shared store for reversible secrets:
 * connectors' outbound credentials ({@code entity = connection}), LLM provider API keys
 * ({@code entity = llm_provider}) and so on.
 *
 * <p>The scheme: a random per-row DEK encrypts {@code encrypted_data} (AES-256-GCM, IV = {@code iv});
 * the DEK itself is encrypted with the KEK (a single source, see {@code SecretEncryptionService}) and
 * stored in {@code encrypted_dek}. When encrypting the DEK the KEK mixes in AAD =
 * {@code entity + owner_id} — a row cannot be decrypted after being moved to another owner.
 * {@code owner_id} is NOT stored in the table: it is a binding supplied by the caller (an owner knows
 * its own id).
 */
@Entity
@Table(name = "secrets")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Secret extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Name of the owning entity (part of the AAD): e.g. {@code connection}, {@code llm_provider}. */
    @Column(name = "entity", nullable = false, columnDefinition = "TEXT")
    private String entity;

    /** The DEK encrypted with the KEK (format [IV(12)][ciphertext+tag]), base64. */
    @Column(name = "encrypted_dek", nullable = false, columnDefinition = "TEXT")
    private String encryptedDek;

    /** IV of the data cipher (base64, 12 bytes). */
    @Column(name = "iv", nullable = false, columnDefinition = "TEXT")
    private String iv;

    /** The payload encrypted with the DEK (base64, without the IV — the IV is in {@link #iv}). */
    @Column(name = "encrypted_data", nullable = false, columnDefinition = "TEXT")
    private String encryptedData;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;
}
