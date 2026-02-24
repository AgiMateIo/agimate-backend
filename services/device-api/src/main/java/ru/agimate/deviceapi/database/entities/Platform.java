package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.common.util.UUIDUtils;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "platforms")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Platform extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "pub_id", unique = true, nullable = false)
    @Builder.Default
    private UUID pubId = UUIDUtils.generateUUIDv8();

    @Column(name = "code", unique = true, nullable = false, columnDefinition = "TEXT")
    private String code;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "icon_url", columnDefinition = "TEXT")
    private String iconUrl;

    @Column(name = "category", nullable = false, columnDefinition = "TEXT")
    private String category;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "credential_fields", nullable = false, columnDefinition = "JSONB")
    private List<String> credentialFields;

    @Column(name = "supports_webhooks", nullable = false)
    @Builder.Default
    private Boolean supportsWebhooks = false;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;
}
