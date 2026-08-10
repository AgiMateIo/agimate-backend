package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A tool of a dynamic connector instance (MCP server, connected app). Generalises the former
 * {@code mcp_tool} cache: the set is discovered at runtime ({@code tools/list} / app link) and
 * cached here so the worker and the policy UI can see the list without reaching the source on the
 * hot path.
 *
 * <p>Schemas are kept as raw JSON text for fidelity to an arbitrary JSON Schema. The business key
 * {@code (connection_id, name)} is partial unique {@code WHERE deleted_at IS NULL}.
 */
@Entity
@Table(name = "connection_tools")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionTool extends BaseEntity {

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

    /** Raw input JSON Schema exactly as it came from the source; {@code null} — empty or absent. */
    @Column(name = "input_schema", columnDefinition = "TEXT")
    private String inputSchema;

    /** Raw output JSON Schema; {@code null} when the source did not provide one. */
    @Column(name = "output_schema", columnDefinition = "TEXT")
    private String outputSchema;

    /** JSON of the hints {@code {readOnlyHint,destructiveHint,idempotentHint,openWorldHint}}; {@code null} — defaults. */
    @Column(name = "annotations", columnDefinition = "TEXT")
    private String annotations;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
