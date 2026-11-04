package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.model.AppSkill;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "apps")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class App extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "connector_code", nullable = false, columnDefinition = "TEXT")
    private String connectorCode;

    /**
     * The app kind the client declares itself ({@code desktop}, {@code chromium}); {@code null} until
     * the first link carrying the {@code app} block. A row created before the client sent that block
     * holds the old user label here — the transition marker is empty {@link #title}/{@link #version}.
     */
    @Column(name = "code", columnDefinition = "TEXT")
    private String code;

    @Column(name = "title", columnDefinition = "TEXT")
    private String title;

    /** Client version; metadata for the UI and support, not a decision input. */
    @Column(name = "version", columnDefinition = "TEXT")
    private String version;

    /** The last skill declaration the app sent; the skill row is the user's copy of it. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill", columnDefinition = "JSONB")
    private AppSkill skill;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "key_hash", nullable = false, columnDefinition = "TEXT")
    private String keyHash;

    @Column(name = "key_id", nullable = false, columnDefinition = "TEXT")
    private String keyId;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "device_id", columnDefinition = "TEXT")
    private String deviceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "info", columnDefinition = "JSONB")
    private Map<String, Object> info;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "triggers", columnDefinition = "JSONB")
    private Map<String, Object> triggers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tools", columnDefinition = "JSONB")
    private Map<String, Object> tools;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isActive() {
        return enabled && !isDeleted();
    }

    public boolean isLinked() {
        return deviceId != null;
    }

    public void disconnect() {
        this.deviceId = null;
        this.info = null;
        this.triggers = null;
        this.tools = null;
    }
}
