package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.common.util.UUIDUtils;

import java.util.UUID;

@Entity
@Table(name = "agent_skills", uniqueConstraints = @UniqueConstraint(columnNames = {"agent_pub_id", "skill_pub_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSkill extends BaseEntity {

    @Id
    @Column(name = "id")
    @Builder.Default
    private UUID id = UUIDUtils.generateUUIDv8();

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;

    @Column(name = "agent_pub_id", nullable = false)
    private UUID agentPubId;

    @Column(name = "skill_pub_id", nullable = false)
    private UUID skillPubId;

    @Column(name = "installed_skill_version")
    private Integer installedSkillVersion;
}
