package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.abac.AccessEffect;

import java.util.UUID;

@Entity
@Table(name = "agent_tool_policies", uniqueConstraints = @UniqueConstraint(
        columnNames = {"agent_id", "connector_code", "connector_identity", "tool_name", "effect"}
))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentToolPolicy extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "connector_code", columnDefinition = "TEXT")
    private String connectorCode;

    @Column(name = "connector_identity", columnDefinition = "TEXT")
    private String connectorIdentity;

    @Column(name = "tool_name", columnDefinition = "TEXT")
    private String toolName;

    @Enumerated(EnumType.STRING)
    @Column(name = "effect", nullable = false, columnDefinition = "TEXT")
    private AccessEffect effect;

    @Column(name = "priority")
    private Integer priority;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "source", columnDefinition = "TEXT")
    private String source;

    @Column(name = "channel_id")
    private UUID channelId;
}
