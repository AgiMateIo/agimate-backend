package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.Disclosure;
import ru.agimate.controlapi.database.model.ConnectorRequirement;

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

    /** Whether the body goes into a run's prompt up front (EAGER) or on a {@code load_skill} call (LAZY). */
    @Enumerated(EnumType.STRING)
    @Column(name = "disclosure", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private Disclosure disclosure = Disclosure.EAGER;

    /** The body of SKILL.md without the frontmatter — the skill's content. */
    @Column(name = "md_content", nullable = false, columnDefinition = "TEXT")
    private String mdContent;

    /**
     * The connectors the skill requires, one {@link ConnectorRequirement} per declared key — JSONB, as
     * the declaration is a document: it arrives whole from the frontmatter and is versioned with
     * {@link #version}. Filtered by code in {@link ru.agimate.controlapi.database.repositories.SkillSpecs#hasConnector(String)}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "connectors", nullable = false, columnDefinition = "JSONB")
    @Builder.Default
    private List<ConnectorRequirement> connectors = new ArrayList<>();

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

    /** Distinct connector codes in declaration order — the view of the readers that work by code. */
    public List<String> getConnectorCodes() {
        return ConnectorRequirement.codes(connectors);
    }

    public ConnectorRequirement requirement(String key) {
        return connectors.stream().filter(r -> r.key().equals(key)).findFirst().orElse(null);
    }
}
