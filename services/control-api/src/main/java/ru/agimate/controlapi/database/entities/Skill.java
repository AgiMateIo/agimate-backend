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
import java.util.UUID;

@Entity
@Table(name = "skills")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Skill extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Stable skill code: the key {@code (user_id, name)}, referenced by {@code preset.skill_names}. */
    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    /** Human-readable display name (localisable in the future); {@code null} → falls back to {@link #name}. */
    @Column(name = "title", columnDefinition = "TEXT")
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** The body of SKILL.md without the frontmatter — the skill's content. */
    @Column(name = "md_content", nullable = false, columnDefinition = "TEXT")
    private String mdContent;

    /**
     * Connectors the skill requires (Postgres {@code text[]}).
     * <p>
     * {@code length} is what makes the element {@code text} rather than {@code varchar}: without it
     * Hibernate renders array literals as {@code cast(array[?] as varchar array)}, and Postgres has no
     * {@code text[] @> varchar[]} operator — see
     * {@link ru.agimate.controlapi.database.repositories.SkillSpecs#hasConnector(String)}.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "connector_codes", nullable = false, columnDefinition = "text[]", length = Length.LONG32)
    @Builder.Default
    private List<String> connectorCodes = new ArrayList<>();

    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "is_public", nullable = false)
    @Builder.Default
    private Boolean isPublic = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
