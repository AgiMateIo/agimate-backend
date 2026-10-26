package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.util.UUID;

/**
 * A conversation starter's state on an agent (docs/decisions/agent-greeting.md). An agent's own
 * starter lives here whole ({@link #agentSkillId} {@code null}); a skill's starter is computed from the
 * binding and gets a row only to remember that the user hid it — {@link #text} is then its key within
 * the binding. Both FKs cascade in the database, so unbinding a skill takes its starters' state along.
 */
@Entity
@Table(name = "agent_starters", uniqueConstraints =
        @UniqueConstraint(name = "uq_agent_starters_agent_skill_id_text", columnNames = {"agent_skill_id", "text"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStarter extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "agent_skill_id")
    private UUID agentSkillId;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(name = "hidden", nullable = false)
    @Builder.Default
    private boolean hidden = false;

    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    public boolean isAgentOwn() {
        return agentSkillId == null;
    }
}
