package ru.agimate.deviceapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.deviceapi.database.enums.BoardTaskStatus;
import ru.agimate.deviceapi.database.enums.BoardTaskType;

import java.util.UUID;

@Entity
@Table(name = "board_tasks")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BoardTask extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "board_id", nullable = false)
    private UUID boardId;

    @Column(name = "user_pub_id", nullable = false)
    private UUID userPubId;

    @Column(name = "parent_task_id")
    private UUID parentTaskId;

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
    private UUID createdByAgentId;

    @Column(name = "assignee_agent_id")
    private UUID assigneeAgentId;
}
