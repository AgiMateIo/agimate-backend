package ru.agimate.controlapi.connectors.internal.board;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.BaseHttpStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgenticTeam;
import ru.agimate.controlapi.database.entities.Board;
import ru.agimate.controlapi.database.enums.BoardTaskStatus;
import ru.agimate.controlapi.database.enums.BoardTaskType;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgenticTeamRepository;
import ru.agimate.controlapi.database.repositories.BoardRepository;
import ru.agimate.controlapi.service.board.BoardService;
import ru.agimate.controlapi.service.dto.board.BoardTaskCommentCreateCommand;
import ru.agimate.controlapi.service.dto.board.BoardTaskCreateCommand;
import ru.agimate.controlapi.service.dto.board.BoardTaskEditCommand;
import ru.agimate.controlapi.service.dto.board.BoardTaskResponse;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.FileStorageService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tools of the board connector — a thin adapter over the core {@link BoardService}. The context
 * (agentId, userId) arrives through {@link ConnectorEnvHolder}. The core's domain
 * {@link BaseHttpStatusException}s are translated into {@link ConnectorException} at the plugin's
 * boundary, so the message reaches the agent and HTTP exceptions do not leak into the connector layer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BoardToolService {

    /** A well-formed agf reference: the prefix plus a uuid. */
    private static final Pattern FILE_REF = Pattern.compile(
            FileIds.PREFIX + "[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    /** Anything that looks like an agf reference, invented ones such as {@code agf_hermit.png} included. */
    private static final Pattern FILE_REF_LIKE = Pattern.compile(FileIds.PREFIX + "[\\w.\\-]+");

    private final BoardService boardService;
    private final AgentRepository agentRepository;
    private final AgenticTeamRepository agenticTeamRepository;
    private final BoardRepository boardRepository;
    private final FileStorageService fileStorageService;

    @Tool(name = "get_tasks",
            description = "List board tasks grouped by status — compact (no descriptions, use get_task "
                    + "for details). Optional filters: status, assignee",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> getTasks(
            @ToolParam(value = "Filter by status (BACKLOG, IN_PROGRESS, REVIEW, DONE)", required = false)
            String status,
            @ToolParam(value = "Filter by assignee: agent public ID or 'me'", required = false)
            String assigneeAgentId) {
        Agent agent = resolveAgent();
        Board board = resolveBoard(agent);
        BoardTaskStatus statusFilter = parseStatus(status);
        UUID assigneeFilter = resolveAgentRef(assigneeAgentId, agent);
        var result = domain(() ->
                boardService.getTasksByStatus(board.getId(), userId(), statusFilter, assigneeFilter));
        // A compact listing: description and timestamps are left to get_task — an overview of the board does not bloat the context.
        Map<String, Object> grouped = new LinkedHashMap<>();
        result.tasks().forEach((s, list) ->
                grouped.put(s.name(), list.stream().map(BoardToolService::compactTask).toList()));
        return Map.of("tasks", grouped);
    }

    @Tool(name = "get_task",
            description = "Get a task card: full task with description, closest epic, parent task, "
                    + "subtasks and recent comments",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> getTask(
            @ToolParam("Task public ID") String taskId) {
        UUID taskUuid = parseUuid(taskId, "taskId");
        var result = domain(() -> boardService.getTaskCard(null, taskUuid, userId()));
        return Map.of("task", result);
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
        UUID parentId = parentTaskId != null && !parentTaskId.isBlank()
                ? parseUuid(parentTaskId, "parentTaskId") : null;
        UUID assigneeId = assigneeAgentId != null && !assigneeAgentId.isBlank()
                ? parseUuid(assigneeAgentId, "assigneeAgentId") : null;

        var command = new BoardTaskCreateCommand(taskType, title, description,
                agent.getId(), assigneeId, parentId);
        var result = domain(() -> boardService.createTask(board.getId(), userId(), command));
        return Map.of("task", result);
    }

    @Tool(name = "edit_task",
            description = "Edit a task: title, description, assignee and/or status. Omitted fields "
                    + "stay unchanged; at least one is required",
            annotations = @ToolAnnotations(openWorldHint = false))
    public Map<String, Object> editTask(
            @ToolParam("Task public ID") String taskId,
            @ToolParam(value = "New title", required = false) String title,
            @ToolParam(value = "New description", required = false) String description,
            @ToolParam(value = "Assignee: agent public ID or 'me' to take the task. Allowed only if "
                    + "the task is unassigned or assigned to you", required = false)
            String assigneeAgentId,
            @ToolParam(value = "New status (BACKLOG, IN_PROGRESS, REVIEW, DONE)", required = false)
            String status) {
        Agent agent = resolveAgent();
        UUID taskUuid = parseUuid(taskId, "taskId");

        String newTitle = blankToNull(title);
        String newDescription = blankToNull(description);
        UUID assignee = resolveAgentRef(assigneeAgentId, agent);
        BoardTaskStatus newStatus = parseStatus(status);
        if (newTitle == null && newDescription == null && assignee == null && newStatus == null) {
            throw new ConnectorException(
                    "At least one of title, description, assigneeAgentId, status is required");
        }

        var command = new BoardTaskEditCommand(agent.getId(), newTitle, newDescription, assignee, newStatus);
        var result = domain(() -> boardService.editTask(null, taskUuid, userId(), command));
        return Map.of("task", result);
    }

    @Tool(name = "get_comments", description = "Get comments for a task",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> getComments(
            @ToolParam("Task public ID") String taskId) {
        UUID taskUuid = parseUuid(taskId, "taskId");
        var result = domain(() -> boardService.getComments(null, taskUuid, userId()));
        return Map.of("comments", result);
    }

    @Tool(name = "create_comment", description = "Create a comment on a task",
            annotations = @ToolAnnotations(openWorldHint = false))
    public Map<String, Object> createComment(
            @ToolParam("Task public ID") String taskId,
            @ToolParam("Comment content") String content) {
        Agent agent = resolveAgent();

        UUID taskUuid = parseUuid(taskId, "taskId");
        requireResolvableFileRefs(content);
        var command = new BoardTaskCommentCreateCommand(agent.getId(), content);
        var result = domain(() -> boardService.createComment(null, taskUuid, userId(), command));
        return Map.of("comment", result);
    }

    /**
     * A comment is the channel through which agents pass results to each other: a non-existent agf
     * reference (a hallucinated id) would travel down the chain all the way to an attachment in the
     * answer to the user. We reject the comment straight away — the agent learns about the mistake
     * before the task is «accepted», not after.
     */
    private void requireResolvableFileRefs(String content) {
        if (content == null || !content.contains(FileIds.PREFIX)) {
            return;
        }
        List<String> broken = new ArrayList<>();
        Matcher ref = FILE_REF_LIKE.matcher(content);
        while (ref.find()) {
            Matcher strict = FILE_REF.matcher(ref.group());
            if (!strict.lookingAt()) {
                broken.add(ref.group());
            } else if (fileStorageService.findReadable(userId(), strict.group()).isEmpty()) {
                broken.add(strict.group());
            }
        }
        if (!broken.isEmpty()) {
            throw new ConnectorException("Comment references non-existent file(s): "
                    + String.join(", ", broken)
                    + ". Reference only real file ids returned by tools; if there is no real file, "
                    + "report a blocker instead of a result");
        }
    }

    /**
     * Run a core domain operation, translating its HTTP exceptions into {@link ConnectorException}:
     * the message (e.g. «SUBTASK must have a parent task») reaches the agent, and a
     * {@code *StatusException} never leaves the connector layer.
     */
    private <T> T domain(Supplier<T> op) {
        try {
            return op.get();
        } catch (BaseHttpStatusException e) {
            throw new ConnectorException(e.getMessage());
        }
    }

    /** A compact listing row: id/type/title plus addressing (assignee/parent), without the description. */
    private static Map<String, Object> compactTask(BoardTaskResponse task) {
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("id", task.id());
        compact.put("type", task.type().name());
        compact.put("title", task.title());
        if (task.assigneeAgentId() != null) {
            compact.put("assigneeAgentId", task.assigneeAgentId());
        }
        if (task.parentTaskId() != null) {
            compact.put("parentTaskId", task.parentTaskId());
        }
        return compact;
    }

    /** {@code null}-tolerant parsing of a status; blank → {@code null} (the filter or field was not given). */
    private static BoardTaskStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return BoardTaskStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Invalid status: '" + status + "'. "
                    + "Allowed: BACKLOG, IN_PROGRESS, REVIEW, DONE");
        }
    }

    /** An agent reference from an argument: {@code "me"} → the calling agent, otherwise a UUID; blank → {@code null}. */
    private UUID resolveAgentRef(String value, Agent self) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if ("me".equalsIgnoreCase(value)) {
            return self.getId();
        }
        return parseUuid(value, "assigneeAgentId");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Parse a UUID out of an agent's argument, reporting a clear error instead of «Tool execution failed». */
    private static UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ConnectorException("Parameter '" + field + "' is required");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Invalid " + field + ": '" + value + "'");
        }
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
