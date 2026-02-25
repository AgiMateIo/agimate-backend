package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.common.util.UUIDUtils;

import java.util.UUID;

@Entity
@Table(name = "board_task_comments", uniqueConstraints = @UniqueConstraint(columnNames = "pub_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BoardTaskComment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "pub_id", unique = true, nullable = false)
    @Builder.Default
    private UUID pubId = UUIDUtils.generateUUIDv8();

    @Column(name = "board_task_id", nullable = false)
    private Long boardTaskId;

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;
}
