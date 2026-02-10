package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;

import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "device", uniqueConstraints = @UniqueConstraint(columnNames = {"device_id", "user_pub_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Device extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "os", nullable = false, columnDefinition = "TEXT")
    private String os;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "triggers", columnDefinition = "JSONB")
    private Map<String, Object> triggers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actions", columnDefinition = "JSONB")
    private Map<String, Object> actions;

    @OneToOne
    @JoinColumn(name = "device_auth_key_id")
    private DeviceAuthKey deviceAuthKey;

}