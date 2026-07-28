package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A «hot» memory note — a journal row per space ({@code scope_id} = agentId). Adding one is an
 * INSERT (append-only), so concurrent writes never conflict. Consolidation claims a batch of notes
 * ({@code consolidationId} plus {@code claimedAt} as the lease), folds them into cold and deletes them.
 */
@Entity
@Table(name = "persistent_memory_hot")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersistentMemoryHot extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Owner of the memory: agentId (AGENT scope) or teamId (TEAM scope). */
    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The session the note came from (tracing); {@code null} for inline notes outside a session. */
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** Id of the consolidation batch that claimed this note; {@code null} — not consolidated yet. */
    @Column(name = "consolidation_id")
    private UUID consolidationId;

    /** Moment of the claim — a lease: once it expires the note is reclaimed by the next consolidation. */
    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;
}
