package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.common.util.UUIDUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "channels")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Channel extends BaseEntity {

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

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "trigger_connector_code", nullable = false, columnDefinition = "TEXT")
    private String triggerConnectorCode;

    @Column(name = "trigger_identity", nullable = false, columnDefinition = "TEXT")
    private String triggerIdentity;

    @Column(name = "trigger_name", nullable = false, columnDefinition = "TEXT")
    private String triggerName;

    @Column(name = "trigger_message_field", nullable = false, columnDefinition = "TEXT")
    private String triggerMessageField;

    @Column(name = "reply_connector_code", nullable = false, columnDefinition = "TEXT")
    private String replyConnectorCode;

    @Column(name = "reply_identity", nullable = false, columnDefinition = "TEXT")
    private String replyIdentity;

    @Column(name = "reply_tool_name", nullable = false, columnDefinition = "TEXT")
    private String replyToolName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reply_tool_params", nullable = false, columnDefinition = "JSONB")
    private Map<String, Object> replyToolParams;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
