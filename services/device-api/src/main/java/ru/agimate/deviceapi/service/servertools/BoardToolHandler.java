package ru.agimate.deviceapi.service.servertools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.controller.manage.dto.*;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.Board;
import ru.agimate.deviceapi.database.enums.BoardTaskStatus;
import ru.agimate.deviceapi.database.enums.BoardTaskType;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
import ru.agimate.deviceapi.database.repositories.BoardRepository;
import ru.agimate.deviceapi.service.BoardService;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class BoardToolHandler implements ServerSideToolHandler {

    private final BoardService boardService;
    private final AgentRepository agentRepository;
    private final BoardRepository boardRepository;

    @Override
    public String getHandlerCode() {
        return "board";
    }

    @Override
    public Map<String, Object> getToolDefinitions() {
        Map<String, Object> tools = new LinkedHashMap<>();

        tools.put("board.get_tasks", Map.of(
                "description", "Get all tasks from the board grouped by status",
                "params", List.of()
        ));

        tools.put("board.create_task", Map.of(
                "description", "Create a new task on the board",
                "params", List.of("type", "title", "description", "parentTaskPubId", "assigneeAgentPubId")
        ));

        tools.put("board.change_task_status", Map.of(
                "description", "Change the status of a task",
                "params", List.of("taskPubId", "status")
        ));

        tools.put("board.get_comments", Map.of(
                "description", "Get comments for a task",
                "params", List.of("taskPubId")
        ));

        tools.put("board.create_comment", Map.of(
                "description", "Create a comment on a task",
                "params", List.of("taskPubId", "content")
        ));

        return tools;
    }

    @Override
    public Map<String, Object> executeTool(String toolName, Map<String, Object> params,
                                            UUID apiKeyPubId, UUID userPubId) {
        Agent agent = agentRepository.findByApiKeyPubId(apiKeyPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));

        if (agent.getAgenticTeamId() == null) {
            throw new BadRequestStatusException("Agent is not part of an agentic team");
        }

        Board board = boardRepository.findByAgenticTeamId(agent.getAgenticTeamId())
                .orElseThrow(() -> new NotFoundStatusException("Board not found for agent's team"));

        return switch (toolName) {
            case "board.get_tasks" -> executeGetTasks(board, userPubId);
            case "board.create_task" -> executeCreateTask(board, userPubId, agent, params);
            case "board.change_task_status" -> executeChangeTaskStatus(userPubId, agent, params);
            case "board.get_comments" -> executeGetComments(userPubId, params);
            case "board.create_comment" -> executeCreateComment(userPubId, agent, params);
            default -> throw new BadRequestStatusException("Unknown board tool: " + toolName);
        };
    }

    private Map<String, Object> executeGetTasks(Board board, UUID userPubId) {
        var result = boardService.getTasksByStatus(board.getPubId(), userPubId);
        return Map.of("tasks", result);
    }

    private Map<String, Object> executeCreateTask(Board board, UUID userPubId, Agent agent, Map<String, Object> params) {
        String typeStr = getStringParam(params, "type");
        BoardTaskType type = BoardTaskType.valueOf(typeStr.toUpperCase());
        String title = getStringParam(params, "title");
        String description = getOptionalStringParam(params, "description");
        UUID parentTaskPubId = getOptionalUuidParam(params, "parentTaskPubId");
        UUID assigneeAgentPubId = getOptionalUuidParam(params, "assigneeAgentPubId");

        var request = new CreateBoardTaskRequest(type, title, description,
                agent.getPubId(), assigneeAgentPubId, parentTaskPubId);
        var result = boardService.createTask(board.getPubId(), userPubId, request);
        return Map.of("task", result);
    }

    private Map<String, Object> executeChangeTaskStatus(UUID userPubId, Agent agent, Map<String, Object> params) {
        UUID taskPubId = getUuidParam(params, "taskPubId");
        String statusStr = getStringParam(params, "status");
        BoardTaskStatus status = BoardTaskStatus.valueOf(statusStr.toUpperCase());

        var request = new UpdateBoardTaskStatusRequest(status, agent.getPubId());
        var result = boardService.changeTaskStatus(taskPubId, userPubId, request);
        return Map.of("task", result);
    }

    private Map<String, Object> executeGetComments(UUID userPubId, Map<String, Object> params) {
        UUID taskPubId = getUuidParam(params, "taskPubId");
        var result = boardService.getComments(taskPubId, userPubId);
        return Map.of("comments", result);
    }

    private Map<String, Object> executeCreateComment(UUID userPubId, Agent agent, Map<String, Object> params) {
        UUID taskPubId = getUuidParam(params, "taskPubId");
        String content = getStringParam(params, "content");

        var request = new CreateBoardTaskCommentRequest(agent.getPubId(), content);
        var result = boardService.createComment(taskPubId, userPubId, request);
        return Map.of("comment", result);
    }

    private String getStringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            throw new BadRequestStatusException("Missing required parameter: " + key);
        }
        return value.toString();
    }

    private String getOptionalStringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value != null ? value.toString() : null;
    }

    private UUID getUuidParam(Map<String, Object> params, String key) {
        return UUID.fromString(getStringParam(params, key));
    }

    private UUID getOptionalUuidParam(Map<String, Object> params, String key) {
        String value = getOptionalStringParam(params, key);
        return value != null ? UUID.fromString(value) : null;
    }
}
