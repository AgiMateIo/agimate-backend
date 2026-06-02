package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.util.UUID;

@Entity
@Table(name = "agent_llms", uniqueConstraints = {
        @UniqueConstraint(name = "uq_agent_llms_agent_name",
                columnNames = {"agent_id", "name"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentLlm extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "llm_provider_id", nullable = false)
    private UUID llmProviderId;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "model", nullable = false, columnDefinition = "TEXT")
    private String model;
}
