package ru.agimate.deviceapi.abac;

import jakarta.persistence.*;
import lombok.*;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.common.util.UUIDUtils;

import java.util.UUID;

@Entity
@Table(name = "access_policies", uniqueConstraints = @UniqueConstraint(
        columnNames = {"agent_name", "connector_name", "connector_identity", "tool_name", "effect"}
))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessPolicy extends BaseEntity {

    @Id
    @Column(name = "id")
    @Builder.Default
    private UUID id = UUIDUtils.generateUUIDv8();

    @Column(name = "agent_name", nullable = false, columnDefinition = "TEXT")
    private String agentName;

    @Column(name = "connector_name", columnDefinition = "TEXT")
    private String connectorName;

    @Column(name = "connector_identity", columnDefinition = "TEXT")
    private String connectorIdentity;

    @Column(name = "tool_name", columnDefinition = "TEXT")
    private String toolName;

    @Enumerated(EnumType.STRING)
    @Column(name = "effect", nullable = false)
    private AccessEffect effect;

    @Column(name = "priority")
    private Integer priority;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
}
