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
 * Refinement of the access rules on top of {@link AgentConnection} — it replaces the separate
 * {@code agent_tool_policies}/{@code agent_trigger_policies}. The model is <b>default-allow</b>:
 * given a binding, a tool or trigger is permitted unless a rule says otherwise.
 *
 * <p>Precedence when resolving {@code (kind, name)}: a rule for the exact {@link #name} → a
 * binding-wide rule ({@code name IS NULL}) → default-allow. That covers both patterns: a deny-list
 * (targeted DENYs) and an allow-list (a binding-wide DENY plus targeted ALLOWs). There is no numeric
 * priority — each {@code (binding, kind, name)} has exactly one active rule.
 *
 * <p>{@link #paramsFilter} is the single «parameter filter»: for {@code TOOL} it constrains the
 * call's arguments, for {@code TRIGGER} the parameters of the incoming event.
 *
 * <p>Uniqueness among active rows: {@code (agent_connection_id, kind, COALESCE(name,'')) WHERE
 * deleted_at IS NULL} — the partial unique index {@code uq_agent_connection_policies_agent_connection_kind_name_active} (via
 * {@code COALESCE}, because NULLs are distinct in Postgres uniqueness).
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

    /** Tool or trigger name; {@code null} = a binding-level rule (applies to the whole connector). */
    @Column(name = "name", columnDefinition = "TEXT")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "effect", nullable = false, columnDefinition = "TEXT")
    private AccessEffect effect;

    /** TOOL — a constraint on call arguments; TRIGGER — a filter on event parameters. */
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
