package ru.agimate.deviceapi.connectors.internal.board;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.connectors.internal.BaseServerSideToolHandler;
import ru.agimate.deviceapi.controller.manage.dto.*;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.AgenticTeam;
import ru.agimate.deviceapi.database.entities.Board;
import ru.agimate.deviceapi.database.enums.BoardTaskStatus;
import ru.agimate.deviceapi.database.enums.BoardTaskType;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
import ru.agimate.deviceapi.database.repositories.AgenticTeamRepository;
import ru.agimate.deviceapi.database.repositories.BoardRepository;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class BoardToolHandler extends BaseServerSideToolHandler {

    public static final String CONNECTOR_CODE = "board";

    private final BoardService boardService;
    private final AgentRepository agentRepository;
    private final AgenticTeamRepository agenticTeamRepository;
    private final BoardRepository boardRepository;

    public BoardToolHandler(BoardService boardService,
                            AgentRepository agentRepository,
                            AgenticTeamRepository agenticTeamRepository,
                            BoardRepository boardRepository) {
        this.boardService = boardService;
        this.agentRepository = agentRepository;
        this.agenticTeamRepository = agenticTeamRepository;
        this.boardRepository = boardRepository;
    }

    @Override
    public String getConnectorCode() {
        return CONNECTOR_CODE;
    }

    @Tool(name = "board.get_tasks", value = "Get all tasks from the board grouped by status")
    public Map<String, Object> getTasks() {
        Board board = resolveBoard(resolveAgent());
        var result = boardService.getTasksByStatus(board.getPubId(), userPubId());
        return Map.of("tasks", result);
    }

    @Tool(name = "board.create_task", value = "Create a new task on the board")
    public Map<String, Object> createTask(
            @P("Task type (EPIC, TASK, SUBTASK)") String type,
            @P("Task title") String title,
            @P("Task description") String description,
            @P("Parent task public ID") String parentTaskPubId,
            @P("Assignee agent public ID") String assigneeAgentPubId) {
        if (type == null || type.isBlank()) {
            throw new BadRequestStatusException("Parameter 'type' is required (EPIC, TASK, SUBTASK)");
        }
        if (title == null || title.isBlank()) {
            throw new BadRequestStatusException("Parameter 'title' is required");
        }

        Agent agent = resolveAgent();
        Board board = resolveBoard(agent);

        BoardTaskType taskType;
        try {
            taskType = BoardTaskType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestStatusException("Invalid task type: '" + type + "'. Allowed: EPIC, TASK, SUBTASK");
        }
        UUID parentId = parentTaskPubId != null ? UUID.fromString(parentTaskPubId) : null;
        UUID assigneeId = assigneeAgentPubId != null ? UUID.fromString(assigneeAgentPubId) : null;

        var request = new CreateBoardTaskRequest(taskType, title, description,
                agent.getPubId(), assigneeId, parentId);
        var result = boardService.createTask(board.getPubId(), userPubId(), request);
        return Map.of("task", result);
    }

    @Tool(name = "board.change_task_status", value = "Change the status of a task")
    public Map<String, Object> changeTaskStatus(
            @P("Task public ID") String taskPubId,
            @P("New status") String status) {
        if (taskPubId == null || taskPubId.isBlank()) {
            throw new BadRequestStatusException("Parameter 'taskPubId' is required");
        }
        if (status == null || status.isBlank()) {
            throw new BadRequestStatusException("Parameter 'status' is required");
        }

        Agent agent = resolveAgent();

        UUID taskId = UUID.fromString(taskPubId);
        BoardTaskStatus taskStatus;
        try {
            taskStatus = BoardTaskStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestStatusException("Invalid status: '" + status + "'");
        }

        var request = new UpdateBoardTaskStatusRequest(taskStatus, agent.getPubId());
        var result = boardService.changeTaskStatus(taskId, userPubId(), request);
        return Map.of("task", result);
    }

    @Tool(name = "board.get_comments", value = "Get comments for a task")
    public Map<String, Object> getComments(
            @P("Task public ID") String taskPubId) {
        UUID taskId = UUID.fromString(taskPubId);
        var result = boardService.getComments(taskId, userPubId());
        return Map.of("comments", result);
    }

    @Tool(name = "board.create_comment", value = "Create a comment on a task")
    public Map<String, Object> createComment(
            @P("Task public ID") String taskPubId,
            @P("Comment content") String content) {
        Agent agent = resolveAgent();

        UUID taskId = UUID.fromString(taskPubId);
        var request = new CreateBoardTaskCommentRequest(agent.getPubId(), content);
        var result = boardService.createComment(taskId, userPubId(), request);
        return Map.of("comment", result);
    }

    private Agent resolveAgent() {
        return agentRepository.findByPubId(agentPubId())
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
    }

    private Board resolveBoard(Agent agent) {
        if (agent.getAgenticTeamId() == null) {
            throw new BadRequestStatusException("Agent is not part of an agentic team");
        }
        AgenticTeam team = agenticTeamRepository.findById(agent.getAgenticTeamId())
                .orElseThrow(() -> new NotFoundStatusException("Agentic team not found"));
        return boardRepository.findByAgenticTeam(team)
                .orElseThrow(() -> new NotFoundStatusException("Board not found for agent's team"));
    }
}
