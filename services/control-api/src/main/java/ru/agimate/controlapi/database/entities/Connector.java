package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.DefinitionBinding;
import ru.agimate.controlapi.database.enums.ExecutionKind;
import ru.agimate.controlapi.database.model.ConnectorTraits;

import java.util.Map;

@Entity
@Table(name = "connectors")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Connector extends BaseEntity {

    @Id
    @Column(name = "code", nullable = false, columnDefinition = "TEXT")
    private String code;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Credentials fields of an integration connector: field code → human-readable name. A mirror of
     * the declaration in code, rewritten on every startup; the field types and their optionality live
     * in the handler, and the API reads them from there.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "credential_fields", columnDefinition = "JSONB")
    private Map<String, String> credentialFields;

    // --- Traits (the functional axes), spread across columns — the runtime reads them directly. ---

    /** Who executes a tool call: BACKEND (in-proc) / APP (pushed to the connected app) / LOOPBACK (the agent). */
    @Enumerated(EnumType.STRING)
    @Column(name = "execution_kind", columnDefinition = "TEXT")
    private ExecutionKind executionKind;

    /** Where tools and triggers come from: STATIC (reflection over the handler) / DYNAMIC ({@code connection_tools}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "definition_binding", columnDefinition = "TEXT")
    private DefinitionBinding definitionBinding;

    /** The traits aggregate (for the API and the bootstrap); the runtime reads the individual fields. */
    public ConnectorTraits traits() {
        return new ConnectorTraits(executionKind, definitionBinding);
    }

    public void applyTraits(ConnectorTraits c) {
        this.executionKind = c.executionKind();
        this.definitionBinding = c.definitionBinding();
    }

    /** An integration connector = it has credentials fields (this replaces the check on the former ConnectorType). */
    public boolean isIntegration() {
        return credentialFields != null && !credentialFields.isEmpty();
    }

    /**
     * Instance-bearing is a derived axis (the single point of derivation): the user brings the
     * instance's identity — credentials (integrations) or an app registration (APP).
     * {@code true} → connections are created explicitly, one per instance (sub_code, secrets);
     * {@code false} → a single mode row per user, with access granted by skills. Consistency with
     * the handler's type is guaranteed by a fail-fast invariant in {@code ConnectorBootstrap}.
     */
    public boolean isInstanceBearing() {
        return isIntegration() || executionKind == ExecutionKind.APP;
    }
}
