package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.common.util.UUIDUtils;
import ru.agimate.deviceapi.abac.AccessEffect;

import java.util.UUID;

@Entity
@Table(name = "agent_trigger_policies", uniqueConstraints = @UniqueConstraint(
        columnNames = {"agent_pub_id", "connector_code", "connector_identity", "trigger_name", "effect"}
))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTriggerPolicy extends BaseEntity {

    @Id
    @Column(name = "id")
    @Builder.Default
    private UUID id = UUIDUtils.generateUUIDv8();

    @Column(name = "agent_pub_id", nullable = false)
    private UUID agentPubId;

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;

    @Column(name = "connector_code", columnDefinition = "TEXT")
    private String connectorCode;

    @Column(name = "connector_identity", columnDefinition = "TEXT")
    private String connectorIdentity;

    @Column(name = "trigger_name", columnDefinition = "TEXT")
    private String triggerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "effect", nullable = false, columnDefinition = "TEXT")
    private AccessEffect effect;

    @Column(name = "priority")
    private Integer priority;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "source", columnDefinition = "TEXT")
    private String source;
}
