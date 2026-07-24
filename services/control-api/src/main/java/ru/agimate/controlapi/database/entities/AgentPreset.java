package ru.agimate.controlapi.database.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Пресет роли агента — заготовка для мастера создания. Декларативный контент: instructions
 * копируются в агента (и дальше редактируются свободно), скилы привязываются по ссылке.
 * Системные пресеты сидятся из classpath ({@code presets/<code>/PRESET.md}) идемпотентно по
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

    /** Машинный код-слаг ('personal-assistant') — ключ идемпотентного сидинга. */
    @Column(name = "name", nullable = false, unique = true, columnDefinition = "TEXT")
    private String name;

    /** Человекочитаемое отображаемое имя. */
    @Column(name = "title", nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Заготовка системных инструкций агента. */
    @Column(name = "instructions", nullable = false, columnDefinition = "TEXT")
    private String instructions;

    /** Имена системных скилов пресета; резолвятся по (SYSTEM_USER_ID, name) при листинге. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "skill_names", nullable = false, columnDefinition = "text[]")
    @Builder.Default
    private List<String> skillNames = new ArrayList<>();

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;
}
