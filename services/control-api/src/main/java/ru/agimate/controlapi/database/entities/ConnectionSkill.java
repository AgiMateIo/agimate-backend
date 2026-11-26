package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Length;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A skill declared by a connection: the app's catalogue entry or an MCP server's skill. The row is a
 * declaration, not the user's copy — for apps a copy lives in {@code skills} and points here through
 * {@code connection_skill_id}. {@code content} is authoritative for an app and a cache for MCP; the
 * source is the connector code, see {@code docs/decisions/connection-skills.md}.
 *
 * <p>Unique by {@code (connection_id, uri)} including soft-deleted rows: a removed key that returns
 * revives its row and keeps the copy's link stable.
 */
@Entity
@Table(name = "connection_skills", uniqueConstraints = @UniqueConstraint(
        name = "uq_connection_skills_connection_id_uri", columnNames = {"connection_id", "uri"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionSkill extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    /** Identity of the declared skill: {@code app://<connectionId>/<key>} or the MCP server's URI. */
    @Column(name = "uri", nullable = false, columnDefinition = "TEXT")
    private String uri;

    /** App key or MCP skill name; part of the copy's name. */
    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "title", columnDefinition = "TEXT")
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "starters", nullable = false, columnDefinition = "TEXT[]", length = Length.LONG32)
    @Builder.Default
    private List<String> starters = new ArrayList<>();

    @Column(name = "language", columnDefinition = "TEXT")
    private String language;

    /** The declaration as it came: for MCP the server frontmatter, for an app the supplied metadata. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "frontmatter", columnDefinition = "JSONB")
    private Map<String, Object> frontmatter;

    /** Authoritative body for an app; MCP's cache until {@code resources/read} fills it. */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_digest", columnDefinition = "TEXT")
    private String contentDigest;

    @Column(name = "content_size")
    private Long contentSize;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manifest", columnDefinition = "JSONB")
    private Map<String, Object> manifest;

    @Column(name = "discovered_at")
    private LocalDateTime discoveredAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
