package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A trigger of a dynamic connector instance (a connected app). An informational table: the set is
 * discovered at runtime (app link) and stored here so the available triggers can be checked.
 * {@code params_schema} is raw JSON text. The business key {@code (connection_id, name)} is partial
 * unique {@code WHERE deleted_at IS NULL}.
 */
@Entity
@Table(name = "connection_triggers")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionTrigger extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "title", columnDefinition = "TEXT")
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Raw JSON Schema of the trigger's parameters; {@code null} — absent. */
    @Column(name = "params_schema", columnDefinition = "TEXT")
    private String paramsSchema;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
