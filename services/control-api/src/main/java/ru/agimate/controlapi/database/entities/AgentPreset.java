package ru.agimate.controlapi.database.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.AgentType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An agent role preset — a blank for the creation wizard. Declarative content: instructions are
 * copied into the agent (and freely edited afterwards), skills are bound by reference. System
 * presets are seeded from the classpath ({@code presets/<code>/PRESET.md}) idempotently by
 * {@code code}.
 */
@Entity
@Table(name = "agent_presets", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentPreset extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Machine code slug ('personal-assistant') — the key of idempotent seeding. */
    @Column(name = "name", nullable = false, unique = true, columnDefinition = "TEXT")
    private String name;

    /** Human-readable display name. */
    @Column(name = "title", nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** The blank for the agent's system instructions. */
    @Column(name = "instructions", nullable = false, columnDefinition = "TEXT")
    private String instructions;

    /** Names of the preset's system skills; resolved by (SYSTEM_USER_ID, name) when listing. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "skill_names", nullable = false, columnDefinition = "text[]")
    @Builder.Default
    private List<String> skillNames = new ArrayList<>();

    /**
     * Type of the agent this preset creates; {@code null} — the wizard asks. It is here rather than in
     * the frontend because the preset is what knows whether it builds an external agent: that wizard
     * asks for a delivery transport instead of a model and a prompt.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "agent_type", columnDefinition = "TEXT")
    private AgentType agentType;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;
}
