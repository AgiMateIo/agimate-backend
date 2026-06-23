package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.util.UUID;

/**
 * Свёрнутая («cold») память агента — единственный MD-файл. Один ряд на агента
 * ({@code agent_id} уникален). Пишется только консолидацией; конкурентные записи
 * отсекаются оптимистической блокировкой по {@code version} (см. update_memory / CAS).
 */
@Entity
@Table(name = "persistent_memory_cold", uniqueConstraints = @UniqueConstraint(
        name = "uq_persistent_memory_cold_agent", columnNames = "agent_id"))
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

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String content = "";

    @Column(name = "version", nullable = false)
    @Builder.Default
    private int version = 0;
}
