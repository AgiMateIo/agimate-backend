package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "agent_tools", uniqueConstraints = @UniqueConstraint(columnNames = {"api_key_pub_id", "tool_name"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;

    @Column(name = "api_key_pub_id", nullable = false)
    private UUID apiKeyPubId;

    @Column(name = "tool_name", nullable = false, columnDefinition = "TEXT")
    private String toolName;
}
