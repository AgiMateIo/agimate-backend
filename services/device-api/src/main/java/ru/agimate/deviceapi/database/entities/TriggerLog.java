package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.common.util.UUIDUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "trigger_logs", uniqueConstraints =
        @UniqueConstraint(columnNames = {"user_pub_id", "connector_code", "identity", "trigger_name", "trigger_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TriggerLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "pub_id", unique = true, nullable = false)
    @Builder.Default
    private UUID pubId = UUIDUtils.generateUUIDv8();

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;

    @Column(name = "connector_code", nullable = false, columnDefinition = "TEXT")
    private String connectorCode;

    @Column(name = "identity", nullable = false, columnDefinition = "TEXT")
    private String identity;

    @Column(name = "trigger_id", nullable = false, columnDefinition = "TEXT")
    private String triggerId;

    @Column(name = "trigger_name", nullable = false, columnDefinition = "TEXT")
    private String triggerName;

    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_input", nullable = false, columnDefinition = "JSONB")
    private Map<String, Object> triggerInput;

    @OneToMany(mappedBy = "triggerLog", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<TriggerLogAgent> triggerLogAgents = new ArrayList<>();
}
