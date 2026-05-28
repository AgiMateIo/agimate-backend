package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.common.util.UUIDUtils;

import java.time.LocalDateTime;
import java.util.UUID;

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

    @Column(name = "pub_id", unique = true, nullable = false)
    @Builder.Default
    private UUID pubId = UUIDUtils.generateUUIDv8();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trigger_log_id", nullable = false)
    private TriggerLog triggerLog;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
    private Agent agent;

    @Column(name = "destination", nullable = false, columnDefinition = "TEXT")
    private String destination;

    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    /**
     * Channel session this run writes to, or {@code null} for non-channel runs
     * (e.g. WEBHOOK/CENTRIFUGO delivery). Set by the backend at trigger routing.
     */
    @Column(name = "session_pub_id")
    private UUID sessionPubId;

    /**
     * Run lifecycle for the active-run registry. The single-writer-per-session
     * invariant is enforced at the DB level by a partial unique index on
     * {@code (session_pub_id) WHERE status = 'RUNNING'}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private RunStatus status = RunStatus.ENQUEUED;

    /** TTL backstop on a dead run; set when the run goes RUNNING, no heartbeat. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
}
