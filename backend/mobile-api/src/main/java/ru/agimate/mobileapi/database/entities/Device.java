package ru.agimate.mobileapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.common.util.UUIDUtils;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "device")
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

    @Column(name = "device_id", unique = true, nullable = false)
    private String deviceId;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name = "os", nullable = false, columnDefinition = "TEXT")
    private String os;

    @OneToOne
    @JoinColumn(name = "device_auth_key_id")
    private DeviceAuthKey deviceAuthKey;

}