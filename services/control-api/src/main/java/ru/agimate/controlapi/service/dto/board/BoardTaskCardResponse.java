package ru.agimate.controlapi.service.dto.board;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.BoardTask;
import ru.agimate.controlapi.database.enums.BoardTaskStatus;
import ru.agimate.controlapi.database.enums.BoardTaskType;

import java.util.List;
import java.util.UUID;

@Schema(description = "Board task card: the task with its hierarchy context and recent comments")
public record BoardTaskCardResponse(
        @Schema(description = "The task itself")
        BoardTaskResponse task,

        @Schema(description = "Closest EPIC up the chain (parent for TASK, grandparent for SUBTASK)")
        TaskRef epic,

        @Schema(description = "Direct parent task")
        TaskRef parentTask,

        @Schema(description = "Direct subtasks")
        List<TaskRef> subtasks,

        @Schema(description = "Most recent comments in chronological order (tail)")
        List<BoardTaskCommentResponse> comments
) {

    @Schema(description = "Short task reference")
    public record TaskRef(
            UUID id,
            BoardTaskType type,
            BoardTaskStatus status,
            String title,
            UUID assigneeAgentId
    ) {
        public static TaskRef from(BoardTask task) {
            return new TaskRef(task.getId(), task.getType(), task.getStatus(),
                    task.getTitle(), task.getAssigneeAgentId());
        }
    }
}
