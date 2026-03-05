package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.deviceapi.database.enums.ConnectorType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "credential_fields", columnDefinition = "JSONB")
    private List<String> credentialFields;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features", columnDefinition = "JSONB")
    private Map<String, Object> features;
}
