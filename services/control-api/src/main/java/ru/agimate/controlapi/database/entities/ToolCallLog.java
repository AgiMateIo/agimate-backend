package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.service.dto.IToolResult;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "tool_call_logs", uniqueConstraints = {
        @UniqueConstraint(name = "uq_tool_call_logs_agent_id_external_id",
                columnNames = {"agent_id", "external_id"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallLog extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "connector_code", columnDefinition = "TEXT")
    private String connectorCode;

    @Column(name = "identity", columnDefinition = "TEXT")
    private String identity;

    @Column(name = "external_id", nullable = false, columnDefinition = "TEXT")
    private String externalId;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input", columnDefinition = "JSONB")
    private Map<String, Object> input;

    @Column(name = "agent_session_id", columnDefinition = "TEXT")
    private String agentSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_effect", columnDefinition = "TEXT")
    private AccessEffect accessEffect;

    @Column(name = "finish_at")
    private LocalDateTime finishAt;

    @Column(name = "output", columnDefinition = "TEXT")
    private String output;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    public void applyResult(IToolResult toolResult) {
        this.finishAt = LocalDateTime.now();
        this.output = toolResult.getOutput();
        this.error = toolResult.getError();
    }
}
