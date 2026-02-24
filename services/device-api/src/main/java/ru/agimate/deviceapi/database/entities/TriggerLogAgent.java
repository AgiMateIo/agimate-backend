package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import ru.agimate.common.persistence.BaseEntity;

@Entity
@Table(name = "trigger_log_agents", uniqueConstraints =
        @UniqueConstraint(columnNames = {"trigger_log_id", "agent_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TriggerLogAgent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trigger_log_id", nullable = false)
    private TriggerLog triggerLog;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
    private Agent agent;

    @Column(name = "routed_to", nullable = false, columnDefinition = "TEXT")
    private String routedTo;
}
