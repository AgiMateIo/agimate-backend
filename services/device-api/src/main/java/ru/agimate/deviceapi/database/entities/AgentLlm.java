package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.common.util.UUIDUtils;

import java.util.UUID;

@Entity
@Table(name = "agent_llms", uniqueConstraints = {
        @UniqueConstraint(name = "uq_agent_llms_agent_name",
                columnNames = {"agent_pub_id", "name"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentLlm extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "pub_id", unique = true, nullable = false)
    @Builder.Default
    private UUID pubId = UUIDUtils.generateUUIDv8();

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;

    @Column(name = "agent_pub_id", nullable = false)
    private UUID agentPubId;

    @Column(name = "llm_provider_pub_id", nullable = false)
    private UUID llmProviderPubId;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "model", nullable = false, columnDefinition = "TEXT")
    private String model;
}
