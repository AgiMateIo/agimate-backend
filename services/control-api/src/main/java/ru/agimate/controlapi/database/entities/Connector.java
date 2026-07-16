package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.ExecutionLocus;
import ru.agimate.controlapi.database.enums.IdentityScope;
import ru.agimate.controlapi.database.enums.DefinitionBinding;
import ru.agimate.controlapi.database.enums.TransportDirection;
import ru.agimate.controlapi.database.model.ConnectorTraits;

import java.util.List;
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

    /** Поля credentials integration-коннектора: код поля → человекочитаемое название. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "credential_fields", columnDefinition = "JSONB")
    private Map<String, String> credentialFields;

    // --- Traits (4 оси), разложены по колонкам — рантайм ориентируется на них напрямую. ---

    /** Кто инициирует соединение: OUTBOUND (мы→платформа, secret) / INBOUND (устройство→мы, app). */
    @Enumerated(EnumType.STRING)
    @Column(name = "transport_direction", columnDefinition = "TEXT")
    private TransportDirection transportDirection;

    /** Кто выполняет работу тула: BACKEND (наша инфра) / DELEGATED (внешняя система) / AGENT (вызывающий). */
    @Enumerated(EnumType.STRING)
    @Column(name = "execution_locus", columnDefinition = "TEXT")
    private ExecutionLocus executionLocus;

    /** Откуда тулы/триггеры: STATIC (рефлексия handler'а) / DYNAMIC ({@code connection_tools}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "definition_binding", columnDefinition = "TEXT")
    private DefinitionBinding definitionBinding;

    /**
     * Какие {@link IdentityScope} коннектор поддерживает (type-level). Подключение выбирает один из
     * них в {@code connections.identity_scope}. Один элемент → выбора нет (telegram/mcp → INSTANCE,
     * board → TEAM); несколько → выбор при создании (память → AGENT/TEAM). Список упорядочен:
     * первый элемент — scope по умолчанию.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supported_scopes", columnDefinition = "JSONB")
    private List<IdentityScope> supportedScopes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features", columnDefinition = "JSONB")
    private Map<String, Object> features;

    /** Агрегат traits (для API/бутстрапа); рантайм читает отдельные поля. */
    public ConnectorTraits traits() {
        return new ConnectorTraits(transportDirection, executionLocus, definitionBinding,
                supportedScopes);
    }

    public void applyTraits(ConnectorTraits c) {
        this.transportDirection = c.transportDirection();
        this.executionLocus = c.executionLocus();
        this.definitionBinding = c.definitionBinding();
        this.supportedScopes = c.supportedScopes();
    }

    /** Scope по умолчанию для нового подключения — первый из {@link #supportedScopes}. */
    public IdentityScope resolveDefaultScope() {
        return supportedScopes != null && !supportedScopes.isEmpty() ? supportedScopes.get(0) : null;
    }

    public boolean supportsScope(IdentityScope scope) {
        return supportedScopes != null && supportedScopes.contains(scope);
    }

    /** Integration-коннектор = есть поля credentials (заменяет проверку по бывшему ConnectorType). */
    public boolean isIntegration() {
        return credentialFields != null && !credentialFields.isEmpty();
    }
}
