package ru.agimate.controlapi.connectors.internal.board;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.controller.manage.dto.CreateBoardTaskCommentRequest;
import ru.agimate.controlapi.controller.manage.dto.CreateBoardTaskRequest;
import ru.agimate.controlapi.controller.manage.dto.UpdateBoardTaskStatusRequest;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgenticTeam;
import ru.agimate.controlapi.database.entities.Board;
import ru.agimate.controlapi.database.enums.BoardTaskStatus;
import ru.agimate.controlapi.database.enums.BoardTaskType;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgenticTeamRepository;
import ru.agimate.controlapi.database.repositories.BoardRepository;

import java.util.Map;
import java.util.UUID;

/**
 * Тулы board-коннектора. Контекст (agentId, userId) приходит через {@link ConnectorEnvHolder}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BoardToolService {

    private final BoardService boardService;
    private final AgentRepository agentRepository;
    private final AgenticTeamRepository agenticTeamRepository;
    private final BoardRepository boardRepository;

    @Tool(name = "get_tasks", description = "Get all tasks from the board grouped by status",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> getTasks() {
        Board board = resolveBoard(resolveAgent());
        var result = boardService.getTasksByStatus(board.getId(), userId());
        return Map.of("tasks", result);
    }

    @Tool(name = "create_task", description = "Create a new task on the board",
            annotations = @ToolAnnotations(openWorldHint = false))
    public Map<String, Object> createTask(
            @ToolParam("Task type (EPIC, TASK, SUBTASK)") String type,
            @ToolParam("Task title") String title,
            @ToolParam(value = "Task description", required = false) String description,
            @ToolParam(value = "Parent task public ID", required = false) String parentTaskId,
            @ToolParam(value = "Assignee agent public ID", required = false) String assigneeAgentId) {
        if (type == null || type.isBlank()) {
            throw new ConnectorException("Parameter 'type' is required (EPIC, TASK, SUBTASK)");
        }
        if (title == null || title.isBlank()) {
            throw new ConnectorException("Parameter 'title' is required");
        }

        Agent agent = resolveAgent();
        Board board = resolveBoard(agent);

        BoardTaskType taskType;
        try {
            taskType = BoardTaskType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Invalid task type: '" + type + "'. Allowed: EPIC, TASK, SUBTASK");
        }
        UUID parentId = parentTaskId != null ? UUID.fromString(parentTaskId) : null;
        UUID assigneeId = assigneeAgentId != null ? UUID.fromString(assigneeAgentId) : null;

        var request = new CreateBoardTaskRequest(taskType, title, description,
                agent.getId(), assigneeId, parentId);
        var result = boardService.createTask(board.getId(), userId(), request);
        return Map.of("task", result);
    }

    @Tool(name = "change_task_status", description = "Change the status of a task",
            annotations = @ToolAnnotations(openWorldHint = false))
    public Map<String, Object> changeTaskStatus(
            @ToolParam("Task public ID") String taskId,
            @ToolParam("New status") String status) {
        if (taskId == null || taskId.isBlank()) {
            throw new ConnectorException("Parameter 'taskId' is required");
        }
        if (status == null || status.isBlank()) {
            throw new ConnectorException("Parameter 'status' is required");
        }

        Agent agent = resolveAgent();

        UUID taskUuid = UUID.fromString(taskId);
        BoardTaskStatus taskStatus;
        try {
            taskStatus = BoardTaskStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Invalid status: '" + status + "'");
        }

        var request = new UpdateBoardTaskStatusRequest(taskStatus, agent.getId());
        var result = boardService.changeTaskStatus(null, taskUuid, userId(), request);
        return Map.of("task", result);
    }

    @Tool(name = "get_comments", description = "Get comments for a task",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> getComments(
            @ToolParam("Task public ID") String taskId) {
        UUID taskUuid = UUID.fromString(taskId);
        var result = boardService.getComments(null, taskUuid, userId());
        return Map.of("comments", result);
    }

    @Tool(name = "create_comment", description = "Create a comment on a task",
            annotations = @ToolAnnotations(openWorldHint = false))
    public Map<String, Object> createComment(
            @ToolParam("Task public ID") String taskId,
            @ToolParam("Comment content") String content) {
        Agent agent = resolveAgent();

        UUID taskUuid = UUID.fromString(taskId);
        var request = new CreateBoardTaskCommentRequest(agent.getId(), content);
        var result = boardService.createComment(null, taskUuid, userId(), request);
        return Map.of("comment", result);
    }

    private UUID userId() {
        return ConnectorEnvHolder.current().userId();
    }

    private Agent resolveAgent() {
        UUID agentId = ConnectorEnvHolder.current().agentId();
        return agentRepository.findById(agentId)
                .orElseThrow(() -> new ConnectorException("Agent not found"));
    }

    private Board resolveBoard(Agent agent) {
        if (agent.getAgenticTeamId() == null) {
            throw new ConnectorException("Agent is not part of an agentic team");
        }
        AgenticTeam team = agenticTeamRepository.findById(agent.getAgenticTeamId())
                .orElseThrow(() -> new ConnectorException("Agentic team not found"));
        return boardRepository.findByAgenticTeam(team)
                .orElseThrow(() -> new ConnectorException("Board not found for agent's team"));
    }
}
