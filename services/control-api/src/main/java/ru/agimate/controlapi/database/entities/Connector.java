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

    /** Поля credentials integration-коннектора: код поля → человекочитаемое название. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "credential_fields", columnDefinition = "JSONB")
    private Map<String, String> credentialFields;

    // --- Traits (функциональные оси), разложены по колонкам — рантайм читает их напрямую. ---

    /** Кто исполняет вызов тула: BACKEND (in-proc) / DEVICE (push устройству) / LOOPBACK (агент). */
    @Enumerated(EnumType.STRING)
    @Column(name = "execution_kind", columnDefinition = "TEXT")
    private ExecutionKind executionKind;

    /** Откуда тулы/триггеры: STATIC (рефлексия handler'а) / DYNAMIC ({@code connection_tools}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "definition_binding", columnDefinition = "TEXT")
    private DefinitionBinding definitionBinding;

    /** Агрегат traits (для API/бутстрапа); рантайм читает отдельные поля. */
    public ConnectorTraits traits() {
        return new ConnectorTraits(executionKind, definitionBinding);
    }

    public void applyTraits(ConnectorTraits c) {
        this.executionKind = c.executionKind();
        this.definitionBinding = c.definitionBinding();
    }

    /** Integration-коннектор = есть поля credentials (заменяет проверку по бывшему ConnectorType). */
    public boolean isIntegration() {
        return credentialFields != null && !credentialFields.isEmpty();
    }

    /**
     * Экземплярность — выводимая ось (единственная точка деривации): пользователь приносит
     * идентичность экземпляра — credentials (интеграции) или регистрацию устройства (DEVICE).
     * {@code true} → connections создаются явно, по одной на экземпляр (sub_code, секреты);
     * {@code false} → одна строка-режим на пользователя, доступ выдают скиллы.
     * Согласованность с типом хендлера гарантирует fail-fast инвариант в {@code ConnectorBootstrap}.
     */
    public boolean isInstanceBearing() {
        return isIntegration() || executionKind == ExecutionKind.DEVICE;
    }
}
