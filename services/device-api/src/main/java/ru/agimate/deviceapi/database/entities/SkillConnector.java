package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;
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
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "skill_id", nullable = false)
    private Skill skill;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "connector_code", nullable = false, columnDefinition = "TEXT")
    private String connectorCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", columnDefinition = "TEXT")
    private SkillConnectorType type;

    @Column(name = "name", columnDefinition = "TEXT")
    private String name;
}
