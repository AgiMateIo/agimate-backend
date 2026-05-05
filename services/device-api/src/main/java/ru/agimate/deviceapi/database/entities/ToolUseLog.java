package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.common.util.UUIDUtils;
import ru.agimate.deviceapi.abac.AccessEffect;
import ru.agimate.deviceapi.service.dto.IToolResult;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "tool_use_logs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolUseLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "pub_id", unique = true, nullable = false)
    @Builder.Default
    private UUID pubId = UUIDUtils.generateUUIDv8();

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;

    @Column(name = "agent_pub_id", nullable = false)
    private UUID agentPubId;

    @Column(name = "connector_code", columnDefinition = "TEXT")
    private String connectorCode;

    @Column(name = "identity", columnDefinition = "TEXT")
    private String identity;

    @Column(name = "tool_use_id", nullable = false, columnDefinition = "TEXT")
    private String toolUseId;

    @Column(name = "tool_name", nullable = false, columnDefinition = "TEXT")
    private String toolName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input", columnDefinition = "JSONB")
    private Map<String, Object> input;

    @Column(name = "agent_session_id", columnDefinition = "TEXT")
    private String agentSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_effect", columnDefinition = "TEXT")
    private AccessEffect accessEffect;

    @Column(name = "output_at")
    private LocalDateTime outputAt;

    @Column(name = "output", columnDefinition = "TEXT")
    private String output;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    public void applyResult(IToolResult toolResult) {
        this.outputAt = LocalDateTime.now();
        this.output = toolResult.getOutput();
        this.error = toolResult.getError();
    }
}
