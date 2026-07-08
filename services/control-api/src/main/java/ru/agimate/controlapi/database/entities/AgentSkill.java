package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.util.UUID;

/**
 * Привязка «скилл установлен на агента» — M:N между {@code agents} и {@code skills}.
 * FK: {@code agent_id → agents(id)} ON DELETE CASCADE (жёсткое удаление агента снимает привязки);
 * {@code skill_id → skills(id)} без каскада — скиллы удаляются мягко, привязки чистит
 * {@code SkillService.delete}.
 */
@Entity
@Table(name = "agent_skills", uniqueConstraints = @UniqueConstraint(columnNames = {"agent_id", "skill_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSkill extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "installed_skill_version")
    private Integer installedSkillVersion;
}
