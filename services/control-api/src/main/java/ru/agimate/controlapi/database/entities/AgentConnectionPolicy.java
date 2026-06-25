package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.database.enums.PolicyKind;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Уточнение правил доступа поверх {@link AgentConnection} — заменяет раздельные
 * {@code agent_tool_policies}/{@code agent_trigger_policies}. Модель — <b>дефолт-allow</b>: при
 * наличии binding тул/триггер разрешён, если нет противоположного правила.
 *
 * <p>Прецеденс при разрешении {@code (kind, name)}: правило по точному {@link #name} →
 * binding-wide правило ({@code name IS NULL}) → дефолт-allow. Это покрывает оба паттерна:
 * deny-list (точечные DENY) и allow-list (binding-wide DENY + точечные ALLOW). Числового priority
 * нет — на каждый {@code (binding, kind, name)} ровно одно активное правило.
 *
 * <p>{@link #paramsFilter} — единый «фильтр по параметрам»: для {@code TOOL} ограничивает аргументы
 * вызова, для {@code TRIGGER} — параметры входящего события.
 *
 * <p>Уникальность среди активных: {@code (agent_connection_id, kind, COALESCE(name,'')) WHERE
 * deleted_at IS NULL} — partial unique индекс {@code uq_agent_connection_policies_active}
 * (через {@code COALESCE}, т.к. NULL в Postgres-уникальности различны).
 */
@Entity
@Table(name = "agent_connection_policies")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentConnectionPolicy extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "agent_connection_id", nullable = false)
    private UUID agentConnectionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, columnDefinition = "TEXT")
    private PolicyKind kind;

    /** Имя тула/триггера; {@code null} = правило уровня binding (применяется ко всему коннектору). */
    @Column(name = "name", columnDefinition = "TEXT")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "effect", nullable = false, columnDefinition = "TEXT")
    private AccessEffect effect;

    /** TOOL — ограничение аргументов вызова; TRIGGER — фильтр параметров события. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params_filter", columnDefinition = "JSONB")
    private Map<String, Object> paramsFilter;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "source", columnDefinition = "TEXT")
    private String source;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public boolean isBindingWide() {
        return name == null;
    }
}
