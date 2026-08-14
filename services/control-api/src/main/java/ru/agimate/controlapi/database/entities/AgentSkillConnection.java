package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.util.UUID;

/**
 * Which instance a skill means — one row per connector code the skill declares, inside one
 * {@link AgentSkill} binding. It is <b>not</b> an access grant: access stays
 * {@link AgentConnection}, and this only answers «which of the user's two telegrams is the one this
 * skill talks about».
 *
 * <p>It hangs on the agent↔skill binding rather than on the skill: the same skill on two agents is
 * two different instances. For internal connectors the answer is forced (one mode row per user) and
 * is filled in by the server; the row exists all the same, so «satisfied» is decided by one rule for
 * both kinds.
 *
 * <p>Uniqueness: {@code (agent_skill_id, connector_code)} — there cannot be a second answer.
 */
@Entity
@Table(name = "agent_skill_connections",
        uniqueConstraints = @UniqueConstraint(name = "uq_agent_skill_connections_agent_skill_id_connector_code",
                columnNames = {"agent_skill_id", "connector_code"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSkillConnection extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "agent_skill_id", nullable = false)
    private UUID agentSkillId;

    @Column(name = "connector_code", nullable = false, columnDefinition = "TEXT")
    private String connectorCode;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;
}
