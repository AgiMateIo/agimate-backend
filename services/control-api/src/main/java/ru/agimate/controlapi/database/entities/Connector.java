package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.ConnectorType;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, columnDefinition = "TEXT")
    private ConnectorType type;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Поля credentials integration-коннектора: код поля → человекочитаемое название. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "credential_fields", columnDefinition = "JSONB")
    private Map<String, String> credentialFields;

    /**
     * Type-level capability-дескриптор (4 оси): кто инициирует соединение, где исполняется тул,
     * статические/динамические тулы, скоуп шаринга. Источник истины — код (SPI
     * {@code capabilities()}), заполняется бутстрапом. Маршрутизация исполнения читает
     * {@code capabilities.executionLocus}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capabilities", columnDefinition = "JSONB")
    private ConnectorCapabilities capabilities;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features", columnDefinition = "JSONB")
    private Map<String, Object> features;
}
