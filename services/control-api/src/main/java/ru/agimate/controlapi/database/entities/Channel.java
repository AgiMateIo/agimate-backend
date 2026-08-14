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

/**
 * A channel of interaction between an agent and a user (how the dialogue is built). The business key
 * {@code (agent_id, connector_code, connection_id)} is unique among active channels — enforced by
 * the partial index {@code uq_channels_agent_id_connector_code_connection_id_active}
 * ({@code WHERE deleted_at IS NULL}). JPA {@code @UniqueConstraint} cannot express a partial
 * condition, so it is not duplicated here.
 */
@Entity
@Table(name = "channels")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Channel extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    /** Name of the {@code ChannelHandler} serving this channel (see ChannelHandlerRegistry). */
    @Column(name = "channel_handler", nullable = false, columnDefinition = "TEXT")
    private String channelHandler;

    /** The connector triggers come from (and, as a rule, replies go to). */
    @Column(name = "connector_code", nullable = false, columnDefinition = "TEXT")
    private String connectorCode;

    /** The connector instance owning the channel ({@code connections.id}); the source of the trigger's route. */
    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    /** Arbitrary handler settings (reply target, templates, messageField and so on). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false, columnDefinition = "JSONB")
    private Map<String, Object> config;

    /**
     * Filter on incoming trigger parameters (chat filtering) — the «how» layer. Applied while
     * resolving the route ({@code ChannelRouteResolver}); no match → delivery over this channel is
     * skipped. It lives here and not on a policy because it decides «how», not «who».
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_filter", columnDefinition = "JSONB")
    private Map<String, Object> inputFilter;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isActive() {
        return !isDeleted();
    }
}
