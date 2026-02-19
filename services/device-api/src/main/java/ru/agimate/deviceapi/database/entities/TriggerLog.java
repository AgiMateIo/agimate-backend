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
@Table(name = "trigger_logs")
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_auth_key_id", nullable = false)
    private App app;

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;

    @Column(name = "trigger_id", columnDefinition = "TEXT")
    private String triggerId;

    @Column(name = "trigger_type", columnDefinition = "TEXT")
    private String triggerType;

    @Column(name = "trigger_name", nullable = false, columnDefinition = "TEXT")
    private String triggerName;

    @Column(name = "trigger_source", columnDefinition = "TEXT")
    private String triggerSource;

    @Column(name = "request_device_id", columnDefinition = "TEXT")
    private String requestDeviceId;

    @Column(name = "linked_device_id", columnDefinition = "TEXT")
    private String linkedDeviceId;

    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_data", nullable = false, columnDefinition = "JSONB")
    private Map<String, Object> triggerData;

    @OneToMany(mappedBy = "triggerLog", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<TriggerLogAgent> triggerLogAgents = new ArrayList<>();
}
