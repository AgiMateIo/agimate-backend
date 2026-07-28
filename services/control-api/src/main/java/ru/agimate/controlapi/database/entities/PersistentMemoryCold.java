package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.util.UUID;

/**
 * Consolidated («cold») memory — a single MD file per space ({@code scope_id} = agentId: memory is
 * personal). One row per space ({@code scope_id} is unique). Written only by consolidation;
 * concurrent writes are rejected by optimistic locking on {@code version}.
 */
@Entity
@Table(name = "persistent_memory_cold", uniqueConstraints = @UniqueConstraint(
        name = "uq_persistent_memory_cold_scope", columnNames = "scope_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersistentMemoryCold extends BaseEntity {

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

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String content = "";

    @Column(name = "version", nullable = false)
    @Builder.Default
    private int version = 0;
}
