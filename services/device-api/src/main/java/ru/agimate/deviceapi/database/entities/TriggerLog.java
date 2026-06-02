package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

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
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

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
