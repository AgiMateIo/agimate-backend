package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.ExecutionLocus;
import ru.agimate.controlapi.database.enums.SharingScope;
import ru.agimate.controlapi.database.enums.ToolBinding;
import ru.agimate.controlapi.database.enums.TransportDirection;
import ru.agimate.controlapi.database.model.ConnectorCapabilities;

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

    // --- Capabilities (4 оси), разложены по колонкам — рантайм ориентируется на них напрямую. ---

    /** Кто инициирует соединение: OUTBOUND (мы→платформа, secret) / INBOUND (устройство→мы, app). */
    @Enumerated(EnumType.STRING)
    @Column(name = "transport_direction", columnDefinition = "TEXT")
    private TransportDirection transportDirection;

    /** Где исполняется тул: BACKEND (in-proc) / EXTERNAL (устройство) / AGENT (агент). */
    @Enumerated(EnumType.STRING)
    @Column(name = "execution_locus", columnDefinition = "TEXT")
    private ExecutionLocus executionLocus;

    /** Откуда тулы/триггеры: STATIC (рефлексия handler'а) / DYNAMIC ({@code connection_tools}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "tool_binding", columnDefinition = "TEXT")
    private ToolBinding toolBinding;

    /** Скоуп шаринга: PRIVATE / TEAM_SHARED / GLOBAL. */
    @Enumerated(EnumType.STRING)
    @Column(name = "sharing_scope", columnDefinition = "TEXT")
    private SharingScope sharingScope;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features", columnDefinition = "JSONB")
    private Map<String, Object> features;

    /** Агрегат capabilities (для API/бутстрапа); рантайм читает отдельные поля. */
    public ConnectorCapabilities capabilities() {
        return new ConnectorCapabilities(transportDirection, executionLocus, toolBinding, sharingScope);
    }

    public void applyCapabilities(ConnectorCapabilities c) {
        this.transportDirection = c.transportDirection();
        this.executionLocus = c.executionLocus();
        this.toolBinding = c.toolBinding();
        this.sharingScope = c.sharingScope();
    }

    /** Integration-коннектор = есть поля credentials (заменяет проверку по бывшему ConnectorType). */
    public boolean isIntegration() {
        return credentialFields != null && !credentialFields.isEmpty();
    }
}
