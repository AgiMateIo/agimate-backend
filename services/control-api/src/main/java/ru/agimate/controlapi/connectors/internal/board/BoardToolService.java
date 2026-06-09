package ru.agimate.controlapi.connectors.internal.board;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.controlapi.connectors.core.ConnectorContextHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
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
 * Тулы board-коннектора. Контекст (agentId, userId) приходит через {@link ConnectorContextHolder}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BoardToolService {

    private final BoardService boardService;
    private final AgentRepository agentRepository;
    private final AgenticTeamRepository agenticTeamRepository;
    private final BoardRepository boardRepository;

    @Tool(name = "board.get_tasks", value = "Get all tasks from the board grouped by status")
    public Map<String, Object> getTasks() {
        Board board = resolveBoard(resolveAgent());
        var result = boardService.getTasksByStatus(board.getId(), userId());
        return Map.of("tasks", result);
    }

    @Tool(name = "board.create_task", value = "Create a new task on the board")
    public Map<String, Object> createTask(
            @P("Task type (EPIC, TASK, SUBTASK)") String type,
            @P("Task title") String title,
            @P("Task description") String description,
            @P("Parent task public ID") String parentTaskId,
            @P("Assignee agent public ID") String assigneeAgentId) {
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

    @Tool(name = "board.change_task_status", value = "Change the status of a task")
    public Map<String, Object> changeTaskStatus(
            @P("Task public ID") String taskId,
            @P("New status") String status) {
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
        var result = boardService.changeTaskStatus(taskUuid, userId(), request);
        return Map.of("task", result);
    }

    @Tool(name = "board.get_comments", value = "Get comments for a task")
    public Map<String, Object> getComments(
            @P("Task public ID") String taskId) {
        UUID taskUuid = UUID.fromString(taskId);
        var result = boardService.getComments(taskUuid, userId());
        return Map.of("comments", result);
    }

    @Tool(name = "board.create_comment", value = "Create a comment on a task")
    public Map<String, Object> createComment(
            @P("Task public ID") String taskId,
            @P("Comment content") String content) {
        Agent agent = resolveAgent();

        UUID taskUuid = UUID.fromString(taskId);
        var request = new CreateBoardTaskCommentRequest(agent.getId(), content);
        var result = boardService.createComment(taskUuid, userId(), request);
        return Map.of("comment", result);
    }

    private UUID userId() {
        return ConnectorContextHolder.current().userId();
    }

    private Agent resolveAgent() {
        UUID agentId = ConnectorContextHolder.current().agentId();
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
