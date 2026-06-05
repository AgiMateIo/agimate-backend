package ru.agimate.deviceapi.connectors.internal.board;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.controller.manage.dto.*;
import ru.agimate.deviceapi.database.entities.*;
import ru.agimate.deviceapi.database.enums.BoardTaskStatus;
import ru.agimate.deviceapi.database.enums.BoardTaskType;
import ru.agimate.deviceapi.database.repositories.*;
import ru.agimate.deviceapi.service.centrifugo.CentrifugoService;
import ru.agimate.deviceapi.service.dto.board.BoardEventType;
import ru.agimate.deviceapi.service.dto.board.BoardTaskCommentCreatedEvent;
import ru.agimate.deviceapi.service.dto.board.BoardTaskCreatedEvent;
import ru.agimate.deviceapi.service.dto.board.BoardTaskStatusChangedEvent;
import ru.agimate.deviceapi.service.trigger.Trigger;
import ru.agimate.deviceapi.service.trigger.TriggerAudience;
import ru.agimate.deviceapi.service.trigger.TriggerRouterService;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoardService {

    private final BoardRepository boardRepository;
    private final BoardTaskRepository boardTaskRepository;
    private final BoardTaskCommentRepository boardTaskCommentRepository;
    private final AgenticTeamRepository agenticTeamRepository;
    private final AgentRepository agentRepository;
    private final TriggerRouterService triggerRouterService;
    private final CentrifugoService centrifugoService;

    // ---- Board CRUD ----

    public List<BoardResponse> getAllForUser(UUID userId) {
        List<Board> boards = boardRepository.findByUserId(userId);
        return boards.stream()
                .map(board -> BoardResponse.from(board, board.getAgenticTeam()))
                .toList();
    }

    public BoardResponse getById(UUID id, UUID userId) {
        Board board = findBoardById(id);
        validateBoardOwnership(board, userId);
        return BoardResponse.from(board, board.getAgenticTeam());
    }

    @Transactional
    public BoardResponse create(UUID userId, CreateBoardRequest request) {
        AgenticTeam team = agenticTeamRepository.findById(request.agenticTeamId())
                .orElseThrow(() -> new NotFoundStatusException("Agentic team not found"));
        if (!team.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied to the specified team");
        }
        if (boardRepository.existsByAgenticTeam(team)) {
            throw new BadRequestStatusException("Board already exists for this agentic team");
        }

        Board board = Board.builder()
                .userId(userId)
                .agenticTeam(team)
                .name(request.name())
                .description(request.description())
                .build();
        board = boardRepository.save(board);

        log.info("Created board '{}' for agenticTeam={}, user={}", request.name(), team.getId(), userId);
        return BoardResponse.from(board, team);
    }

    // ---- Tasks ----

    public BoardTasksByStatusResponse getTasksByStatus(UUID boardId, UUID userId) {
        Board board = findBoardById(boardId);
        validateBoardOwnership(board, userId);

        List<BoardTask> tasks = boardTaskRepository.findByBoardIdOrderByCreatedAtDesc(board.getId());
        Map<UUID, Agent> agentsById = resolveAgentsForTasks(tasks);
        Map<UUID, BoardTask> tasksById = tasks.stream()
                .collect(Collectors.toMap(BoardTask::getId, Function.identity()));

        Map<BoardTaskStatus, List<BoardTaskResponse>> grouped = new LinkedHashMap<>();
        for (BoardTaskStatus status : BoardTaskStatus.values()) {
            grouped.put(status, new ArrayList<>());
        }

        for (BoardTask task : tasks) {
            BoardTaskResponse response = toBoardTaskResponse(task, agentsById, tasksById);
            grouped.get(task.getStatus()).add(response);
        }

        return new BoardTasksByStatusResponse(grouped);
    }

    @Transactional
    public BoardTaskResponse createTask(UUID boardId, UUID userId, CreateBoardTaskRequest request) {
        Board board = findBoardById(boardId);
        validateBoardOwnership(board, userId);

        Agent createdBy = resolveTeamAgent(board, request.createdByAgentId());
        Agent assignee = null;
        if (request.assigneeAgentId() != null) {
            assignee = resolveTeamAgent(board, request.assigneeAgentId());
        }

        if (request.type() == BoardTaskType.EPIC && request.parentTaskId() != null) {
            throw new BadRequestStatusException("EPIC tasks cannot have a parent");
        }
        if (request.type() == BoardTaskType.SUBTASK && request.parentTaskId() == null) {
            throw new BadRequestStatusException("SUBTASK must have a parent task");
        }

        UUID parentTaskId = null;
        if (request.parentTaskId() != null) {
            BoardTask parentTask = boardTaskRepository.findById(request.parentTaskId())
                    .orElseThrow(() -> new NotFoundStatusException("Parent task not found"));
            if (!parentTask.getBoardId().equals(board.getId())) {
                throw new BadRequestStatusException("Parent task does not belong to this board");
            }
            if (request.type() == BoardTaskType.SUBTASK && parentTask.getType() != BoardTaskType.TASK) {
                throw new BadRequestStatusException("SUBTASK parent must be a TASK");
            }
            if (request.type() == BoardTaskType.TASK && parentTask.getType() != BoardTaskType.EPIC) {
                throw new BadRequestStatusException("TASK parent must be an EPIC");
            }
            parentTaskId = parentTask.getId();
        }

        BoardTask task = BoardTask.builder()
                .boardId(board.getId())
                .userId(userId)
                .parentTaskId(parentTaskId)
                .type(request.type())
                .title(request.title())
                .description(request.description())
                .createdByAgentId(createdBy.getId())
                .assigneeAgentId(assignee != null ? assignee.getId() : null)
                .build();
        task = boardTaskRepository.save(task);

        log.info("Created board task '{}' on board={}", request.title(), boardId);

        Map<String, Object> triggerData = new LinkedHashMap<>();
        triggerData.put("boardId", board.getId().toString());
        triggerData.put("taskId", task.getId().toString());
        triggerData.put("createdByAgentId", createdBy.getId().toString());
        if (assignee != null) {
            triggerData.put("assigneeAgentId", assignee.getId().toString());
        }
        triggerData.put("type", task.getType().name());
        triggerData.put("title", task.getTitle());
        triggerData.put("description", task.getDescription());
        if (parentTaskId != null) {
            triggerData.put("parentTaskId", parentTaskId.toString());
        }

        TriggerAudience audience = new TriggerAudience(
                createdBy.getId(),
                assignee != null ? List.of(assignee.getId()) : List.of()
        );

        Trigger trigger = Trigger.createWithAudience(
                BoardToolHandler.CONNECTOR_CODE,
                board.getId().toString(),
                "trigger.board.task_created",
                triggerData,
                audience
        );

        triggerRouterService.routeInternalTrigger(userId, board.getAgenticTeam().getId(), trigger);

        publishBoardEvent(userId, board.getId(), BoardEventType.TASK_CREATED,
                new BoardTaskCreatedEvent(
                        board.getId(),
                        task.getId(),
                        task.getType(),
                        task.getStatus(),
                        task.getTitle(),
                        task.getDescription(),
                        createdBy.getId(),
                        assignee != null ? assignee.getId() : null,
                        parentTaskId
                ));

        return BoardTaskResponse.from(task,
                createdBy.getId(),
                assignee != null ? assignee.getId() : null,
                parentTaskId);
    }

    @Transactional
    public BoardTaskResponse changeTaskStatus(UUID taskId, UUID userId, UpdateBoardTaskStatusRequest request) {
        BoardTask task = boardTaskRepository.findById(taskId)
                .orElseThrow(() -> new NotFoundStatusException("Task not found"));
        Board board = boardRepository.findById(task.getBoardId())
                .orElseThrow(() -> new NotFoundStatusException("Board not found"));
        validateBoardOwnership(board, userId);

        resolveTeamAgent(board, request.agentId());

        BoardTaskStatus oldStatus = task.getStatus();
        task.setStatus(request.status());
        task = boardTaskRepository.save(task);

        log.info("Changed task {} status from {} to {}", taskId, oldStatus, request.status());

        Map<String, Object> triggerData = new LinkedHashMap<>();
        triggerData.put("taskId", task.getId().toString());
        triggerData.put("oldStatus", oldStatus.name());
        triggerData.put("newStatus", request.status().name());

        Map<UUID, Agent> agentsById = resolveAgentsForTasks(List.of(task));
        TriggerAudience audience = new TriggerAudience(
                request.agentId(),
                resolveTaskParticipantIds(task, agentsById)
        );

        Trigger trigger = Trigger.createWithAudience(
                BoardToolHandler.CONNECTOR_CODE,
                board.getId().toString(),
                "trigger.board.task_status_changed",
                triggerData,
                audience
        );

        triggerRouterService.routeInternalTrigger(userId, board.getAgenticTeam().getId(), trigger);

        publishBoardEvent(userId, board.getId(), BoardEventType.TASK_STATUS_CHANGED,
                new BoardTaskStatusChangedEvent(
                        board.getId(),
                        task.getId(),
                        oldStatus,
                        request.status()
                ));

        Map<UUID, BoardTask> tasksById = task.getParentTaskId() != null
                ? boardTaskRepository.findById(task.getParentTaskId())
                    .map(p -> Map.of(p.getId(), p)).orElse(Map.of())
                : Map.of();

        return toBoardTaskResponse(task, agentsById, tasksById);
    }

    // ---- Comments ----

    public List<BoardTaskCommentResponse> getComments(UUID taskId, UUID userId) {
        BoardTask task = boardTaskRepository.findById(taskId)
                .orElseThrow(() -> new NotFoundStatusException("Task not found"));
        Board board = boardRepository.findById(task.getBoardId())
                .orElseThrow(() -> new NotFoundStatusException("Board not found"));
        validateBoardOwnership(board, userId);

        List<BoardTaskComment> comments = boardTaskCommentRepository.findByBoardTaskIdOrderByCreatedAtAsc(task.getId());

        List<UUID> agentIds = comments.stream().map(BoardTaskComment::getAgentId).distinct().toList();
        Map<UUID, Agent> agentsById = agentRepository.findAllById(agentIds).stream()
                .collect(Collectors.toMap(Agent::getId, Function.identity()));

        return comments.stream()
                .map(c -> {
                    Agent agent = agentsById.get(c.getAgentId());
                    return BoardTaskCommentResponse.from(c, agent != null ? agent.getId() : null);
                })
                .toList();
    }

    @Transactional
    public BoardTaskCommentResponse createComment(UUID taskId, UUID userId, CreateBoardTaskCommentRequest request) {
        BoardTask task = boardTaskRepository.findById(taskId)
                .orElseThrow(() -> new NotFoundStatusException("Task not found"));
        Board board = boardRepository.findById(task.getBoardId())
                .orElseThrow(() -> new NotFoundStatusException("Board not found"));
        validateBoardOwnership(board, userId);

        Agent agent = resolveTeamAgent(board, request.agentId());

        BoardTaskComment comment = BoardTaskComment.builder()
                .boardTaskId(task.getId())
                .userId(userId)
                .agentId(agent.getId())
                .content(request.content())
                .build();
        comment = boardTaskCommentRepository.save(comment);

        log.info("Created comment on task {} by agent {}", taskId, request.agentId());

        Map<String, Object> triggerData = new LinkedHashMap<>();
        triggerData.put("taskId", task.getId().toString());
        triggerData.put("commentId", comment.getId().toString());
        triggerData.put("agentId", agent.getId().toString());
        triggerData.put("content", comment.getContent());

        Map<UUID, Agent> agentsById = resolveAgentsForTasks(List.of(task));
        TriggerAudience audience = new TriggerAudience(
                agent.getId(),
                resolveTaskParticipantIds(task, agentsById)
        );

        Trigger trigger = Trigger.createWithAudience(
                BoardToolHandler.CONNECTOR_CODE,
                board.getId().toString(),
                "trigger.board.task_comment_created",
                triggerData,
                audience
        );

        triggerRouterService.routeInternalTrigger(userId, board.getAgenticTeam().getId(), trigger);

        publishBoardEvent(userId, board.getId(), BoardEventType.COMMENT_CREATED,
                new BoardTaskCommentCreatedEvent(
                        board.getId(),
                        task.getId(),
                        comment.getId(),
                        agent.getId(),
                        comment.getContent()
                ));

        return BoardTaskCommentResponse.from(comment, agent.getId());
    }

    // ---- Helpers ----

    private void publishBoardEvent(UUID userId, UUID boardId, String eventType, Object eventData) {
        String channel = "user:" + userId;
        Map<String, String> tags = Map.of(
                "entity", "board.task",
                "boardId", boardId.toString()
        );
        try {
            centrifugoService.publishMessage(channel, eventType, eventData, tags);
        } catch (Exception e) {
            log.warn("Failed to publish board event '{}' to user channel: {}", eventType, e.getMessage());
        }
    }

    private Board findBoardById(UUID id) {
        return boardRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Board not found"));
    }

    private void validateBoardOwnership(Board board, UUID userId) {
        if (!board.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }
    }

    private Agent resolveTeamAgent(Board board, UUID agentId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        if (agent.getAgenticTeamId() == null || !agent.getAgenticTeamId().equals(board.getAgenticTeam().getId())) {
            throw new BadRequestStatusException("Agent does not belong to the board's agentic team");
        }
        return agent;
    }

    private Map<UUID, Agent> resolveAgentsForTasks(List<BoardTask> tasks) {
        Set<UUID> agentIds = new HashSet<>();
        for (BoardTask task : tasks) {
            agentIds.add(task.getCreatedByAgentId());
            if (task.getAssigneeAgentId() != null) {
                agentIds.add(task.getAssigneeAgentId());
            }
        }
        return agentRepository.findAllById(agentIds).stream()
                .collect(Collectors.toMap(Agent::getId, Function.identity()));
    }

    private List<UUID> resolveTaskParticipantIds(BoardTask task, Map<UUID, Agent> agentsById) {
        Set<UUID> ids = new LinkedHashSet<>();
        Agent createdBy = agentsById.get(task.getCreatedByAgentId());
        if (createdBy != null) {
            ids.add(createdBy.getId());
        }
        if (task.getAssigneeAgentId() != null) {
            Agent assignee = agentsById.get(task.getAssigneeAgentId());
            if (assignee != null) {
                ids.add(assignee.getId());
            }
        }
        return List.copyOf(ids);
    }

    private BoardTaskResponse toBoardTaskResponse(BoardTask task, Map<UUID, Agent> agentsById, Map<UUID, BoardTask> tasksById) {
        Agent createdBy = agentsById.get(task.getCreatedByAgentId());
        Agent assignee = task.getAssigneeAgentId() != null ? agentsById.get(task.getAssigneeAgentId()) : null;
        BoardTask parentTask = task.getParentTaskId() != null ? tasksById.get(task.getParentTaskId()) : null;

        return BoardTaskResponse.from(task,
                createdBy != null ? createdBy.getId() : null,
                assignee != null ? assignee.getId() : null,
                parentTask != null ? parentTask.getId() : null);
    }
}
