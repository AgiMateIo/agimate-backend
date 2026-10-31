package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.LlmPurpose;

import java.util.UUID;

/**
 * A platform purpose's chain — the mirror of {@code agent_llms} without an owner: platform
 * configuration belongs to the platform, and every provider row under
 * {@code SystemSkillBootstrap.SYSTEM_USER_ID} is a platform provider. The table is global, so one
 * chain serves every PLATFORM agent (docs/decisions/platform-llm-purposes-and-agent-source.md).
 */
@Entity
@Table(name = "platform_llms", uniqueConstraints = {
        @UniqueConstraint(name = "uq_platform_llms_purpose_priority",
                columnNames = {"purpose", "priority"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformLlm extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "llm_provider_id", nullable = false)
    private UUID llmProviderId;

    @Column(name = "model", nullable = false, columnDefinition = "TEXT")
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private LlmPurpose purpose = LlmPurpose.CHAT;

    /** Position in the purpose's chain; the resolver takes the first eligible row. */
    @Column(name = "priority", nullable = false)
    private int priority;
}
