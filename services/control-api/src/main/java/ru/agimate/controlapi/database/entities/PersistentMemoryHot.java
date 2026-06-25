package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * «Hot» заметка памяти — строка журнала на scope ({@code scope_id}: agentId/teamId). Добавление =
 * INSERT (append-only), поэтому конкурентные записи не конфликтуют. Консолидация клеймит партию заметок
 * ({@code consolidationId} + {@code claimedAt} как лиз), сворачивает их в cold и удаляет.
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

    /** Носитель памяти: agentId (AGENT scope) или teamId (TEAM scope). */
    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Сессия-источник заметки (трейсинг); {@code null} для инлайн-заметок вне сессии. */
    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** Id партии консолидации, заклеймившей эту заметку; {@code null} — ещё не сконсолидирована. */
    @Column(name = "consolidation_id")
    private UUID consolidationId;

    /** Момент клейма — лиз: по истечении заметка реклеймится следующей консолидацией. */
    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;
}
