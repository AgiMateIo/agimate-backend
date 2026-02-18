package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.common.util.UUIDUtils;

import java.util.UUID;

@Entity
@Table(name = "agent_settings", uniqueConstraints = @UniqueConstraint(columnNames = "api_key_pub_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSettings extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "pub_id", unique = true, nullable = false)
    @Builder.Default
    private UUID pubId = UUIDUtils.generateUUIDv8();

    @Column(name = "api_key_pub_id", nullable = false, unique = true)
    private UUID apiKeyPubId;

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;

    @Column(name = "prompt", columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "triggers_allow_all", nullable = false)
    @Builder.Default
    private boolean triggersAllowAll = false;

    @Column(name = "triggers_to", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String triggersTo = "ignore";
}
