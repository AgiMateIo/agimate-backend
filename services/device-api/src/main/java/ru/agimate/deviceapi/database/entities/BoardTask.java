package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.common.util.UUIDUtils;

import java.util.UUID;

@Entity
@Table(name = "board_tasks", uniqueConstraints = @UniqueConstraint(columnNames = "pub_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BoardTask extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "pub_id", unique = true, nullable = false)
    @Builder.Default
    private UUID pubId = UUIDUtils.generateUUIDv8();

    @Column(name = "board_id", nullable = false)
    private Long boardId;

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;

    @Column(name = "parent_task_id")
    private Long parentTaskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, columnDefinition = "TEXT")
    private BoardTaskType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private BoardTaskStatus status = BoardTaskStatus.BACKLOG;

    @Column(name = "title", nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_by_agent_id", nullable = false)
    private Long createdByAgentId;

    @Column(name = "assignee_agent_id")
    private Long assigneeAgentId;
}
