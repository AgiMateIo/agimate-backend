package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "agent_triggers", uniqueConstraints = @UniqueConstraint(columnNames = {"api_key_pub_id", "trigger_name"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTrigger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;

    @Column(name = "api_key_pub_id", nullable = false)
    private UUID apiKeyPubId;

    @Column(name = "trigger_name", nullable = false, columnDefinition = "TEXT")
    private String triggerName;
}
