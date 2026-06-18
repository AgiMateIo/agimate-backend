package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "webhook_delivery_logs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookDeliveryLog extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trigger_log_agent_id", nullable = false)
    private TriggerLogAgent triggerLogAgent;

    @Column(name = "request_url", nullable = false, columnDefinition = "TEXT")
    private String requestUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", nullable = false, columnDefinition = "JSONB")
    private Map<String, Object> requestPayload;

    @Column(name = "response_status_code")
    private Integer responseStatusCode;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "delivered_at", nullable = false)
    @Builder.Default
    private LocalDateTime deliveredAt = LocalDateTime.now();

    public boolean isSuccess() {
        return responseStatusCode != null && responseStatusCode >= 200 && responseStatusCode < 300;
    }
}
