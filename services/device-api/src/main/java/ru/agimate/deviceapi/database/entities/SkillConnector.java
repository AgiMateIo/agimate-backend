package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.common.util.UUIDUtils;
import ru.agimate.deviceapi.database.enums.SkillConnectorType;

import java.util.UUID;

@Entity
@Table(name = "skill_connectors")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillConnector extends BaseEntity {

    @Id
    @Column(name = "id")
    @Builder.Default
    private UUID id = UUIDUtils.generateUUIDv8();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "skill_id", nullable = false)
    private Skill skill;

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;

    @Column(name = "connector_code", nullable = false, columnDefinition = "TEXT")
    private String connectorCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", columnDefinition = "TEXT")
    private SkillConnectorType type;

    @Column(name = "name", columnDefinition = "TEXT")
    private String name;
}
