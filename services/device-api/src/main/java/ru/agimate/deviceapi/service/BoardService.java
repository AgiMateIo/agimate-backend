package ru.agimate.deviceapi.service;

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
    private final BoardTriggerService boardTriggerService;
    private final ConnectorService connectorService;
    private final ru.agimate.deviceapi.service.servertools.BoardToolHandler boardToolHandler;

    // ---- Board CRUD ----

    public List<BoardResponse> getAllForUser(UUID userPubId) {
        List<Board> boards = boardRepository.findByUserPubId(userPubId);
        List<Long> teamIds = boards.stream().map(Board::getAgenticTeamId).toList();
        Map<Long, AgenticTeam> teamsById = agenticTeamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(AgenticTeam::getId, Function.identity()));

        return boards.stream()
                .map(board -> BoardResponse.from(board, teamsById.get(board.getAgenticTeamId())))
                .toList();
    }

    public BoardResponse getByPubId(UUID pubId, UUID userPubId) {
        Board board = findBoardByPubId(pubId);
        validateBoardOwnership(board, userPubId);
        AgenticTeam team = agenticTeamRepository.findById(board.getAgenticTeamId())
                .orElseThrow(() -> new NotFoundStatusException("Agentic team not found"));
        return BoardResponse.from(board, team);
    }

    @Transactional
    public BoardResponse create(UUID userPubId, CreateBoardRequest request) {
        AgenticTeam team = agenticTeamRepository.findByPubId(request.agenticTeamPubId())
                .orElseThrow(() -> new NotFoundStatusException("Agentic team not found"));
        if (!team.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied to the specified team");
        }
        if (boardRepository.existsByAgenticTeamId(team.getId())) {
            throw new BadRequestStatusException("Board already exists for this agentic team");
        }

        Board board = Board.builder()
                .userPubId(userPubId)
                .agenticTeamId(team.getId())
                .name(request.name())
                .description(request.description())
                .build();
        board = boardRepository.save(board);

        connectorService.createServerConnector(
                userPubId,
                "Board",
                "Board tools",
                Map.of(),
                boardToolHandler.getToolDefinitions()
        );

        log.info("Created board '{}' for agenticTeam={}, user={}", request.name(), team.getPubId(), userPubId);
        return BoardResponse.from(board, team);
    }

    // ---- Tasks ----

    public BoardTasksByStatusResponse getTasksByStatus(UUID boardPubId, UUID userPubId) {
        Board board = findBoardByPubId(boardPubId);
        validateBoardOwnership(board, userPubId);

        List<BoardTask> tasks = boardTaskRepository.findByBoardIdOrderByCreatedAtDesc(board.getId());
        Map<Long, Agent> agentsById = resolveAgentsForTasks(tasks);
        Map<Long, BoardTask> tasksById = tasks.stream()
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
    public BoardTaskResponse createTask(UUID boardPubId, UUID userPubId, CreateBoardTaskRequest request) {
        Board board = findBoardByPubId(boardPubId);
        validateBoardOwnership(board, userPubId);

        Agent createdBy = resolveTeamAgent(board, request.createdByAgentPubId());
        Agent assignee = null;
        if (request.assigneeAgentPubId() != null) {
            assignee = resolveTeamAgent(board, request.assigneeAgentPubId());
        }

        if (request.type() == BoardTaskType.EPIC && request.parentTaskPubId() != null) {
            throw new BadRequestStatusException("EPIC tasks cannot have a parent");
        }
        if (request.type() == BoardTaskType.SUBTASK && request.parentTaskPubId() == null) {
            throw new BadRequestStatusException("SUBTASK must have a parent task");
        }

        Long parentTaskId = null;
        UUID parentTaskPubId = null;
        if (request.parentTaskPubId() != null) {
            BoardTask parentTask = boardTaskRepository.findByPubId(request.parentTaskPubId())
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
            parentTaskPubId = parentTask.getPubId();
        }

        BoardTask task = BoardTask.builder()
                .boardId(board.getId())
                .userPubId(userPubId)
                .parentTaskId(parentTaskId)
                .type(request.type())
                .title(request.title())
                .description(request.description())
                .createdByAgentId(createdBy.getId())
                .assigneeAgentId(assignee != null ? assignee.getId() : null)
                .build();
        task = boardTaskRepository.save(task);

        log.info("Created board task '{}' on board={}", request.title(), boardPubId);

        Map<String, Object> triggerData = new LinkedHashMap<>();
        triggerData.put("boardPubId", board.getPubId().toString());
        triggerData.put("taskPubId", task.getPubId().toString());
        triggerData.put("createdByAgentPubId", createdBy.getPubId().toString());
        if (assignee != null) {
            triggerData.put("assigneeAgentPubId", assignee.getPubId().toString());
        }
        triggerData.put("type", task.getType().name());
        triggerData.put("title", task.getTitle());
        triggerData.put("description", task.getDescription());
        if (parentTaskPubId != null) {
            triggerData.put("parentTaskPubId", parentTaskPubId.toString());
        }
        boardTriggerService.fireTrigger(userPubId, board.getAgenticTeamId(),
                "trigger.board.task_created", triggerData);

        return BoardTaskResponse.from(task,
                createdBy.getPubId(),
                assignee != null ? assignee.getPubId() : null,
                parentTaskPubId);
    }

    @Transactional
    public BoardTaskResponse changeTaskStatus(UUID taskPubId, UUID userPubId, UpdateBoardTaskStatusRequest request) {
        BoardTask task = boardTaskRepository.findByPubId(taskPubId)
                .orElseThrow(() -> new NotFoundStatusException("Task not found"));
        Board board = boardRepository.findById(task.getBoardId())
                .orElseThrow(() -> new NotFoundStatusException("Board not found"));
        validateBoardOwnership(board, userPubId);

        resolveTeamAgent(board, request.agentPubId());

        BoardTaskStatus oldStatus = task.getStatus();
        task.setStatus(request.status());
        task = boardTaskRepository.save(task);

        log.info("Changed task {} status from {} to {}", taskPubId, oldStatus, request.status());

        Map<String, Object> triggerData = new LinkedHashMap<>();
        triggerData.put("taskPubId", task.getPubId().toString());
        triggerData.put("oldStatus", oldStatus.name());
        triggerData.put("newStatus", request.status().name());
        boardTriggerService.fireTrigger(userPubId, board.getAgenticTeamId(),
                "trigger.board.task_status_changed", triggerData);

        Map<Long, Agent> agentsById = resolveAgentsForTasks(List.of(task));
        Map<Long, BoardTask> tasksById = task.getParentTaskId() != null
                ? boardTaskRepository.findById(task.getParentTaskId())
                    .map(p -> Map.of(p.getId(), p)).orElse(Map.of())
                : Map.of();

        return toBoardTaskResponse(task, agentsById, tasksById);
    }

    // ---- Comments ----

    public List<BoardTaskCommentResponse> getComments(UUID taskPubId, UUID userPubId) {
        BoardTask task = boardTaskRepository.findByPubId(taskPubId)
                .orElseThrow(() -> new NotFoundStatusException("Task not found"));
        Board board = boardRepository.findById(task.getBoardId())
                .orElseThrow(() -> new NotFoundStatusException("Board not found"));
        validateBoardOwnership(board, userPubId);

        List<BoardTaskComment> comments = boardTaskCommentRepository.findByBoardTaskIdOrderByCreatedAtAsc(task.getId());

        List<Long> agentIds = comments.stream().map(BoardTaskComment::getAgentId).distinct().toList();
        Map<Long, Agent> agentsById = agentRepository.findAllById(agentIds).stream()
                .collect(Collectors.toMap(Agent::getId, Function.identity()));

        return comments.stream()
                .map(c -> {
                    Agent agent = agentsById.get(c.getAgentId());
                    return BoardTaskCommentResponse.from(c, agent != null ? agent.getPubId() : null);
                })
                .toList();
    }

    @Transactional
    public BoardTaskCommentResponse createComment(UUID taskPubId, UUID userPubId, CreateBoardTaskCommentRequest request) {
        BoardTask task = boardTaskRepository.findByPubId(taskPubId)
                .orElseThrow(() -> new NotFoundStatusException("Task not found"));
        Board board = boardRepository.findById(task.getBoardId())
                .orElseThrow(() -> new NotFoundStatusException("Board not found"));
        validateBoardOwnership(board, userPubId);

        Agent agent = resolveTeamAgent(board, request.agentPubId());

        BoardTaskComment comment = BoardTaskComment.builder()
                .boardTaskId(task.getId())
                .userPubId(userPubId)
                .agentId(agent.getId())
                .content(request.content())
                .build();
        comment = boardTaskCommentRepository.save(comment);

        log.info("Created comment on task {} by agent {}", taskPubId, request.agentPubId());

        Map<String, Object> triggerData = new LinkedHashMap<>();
        triggerData.put("taskPubId", task.getPubId().toString());
        triggerData.put("commentPubId", comment.getPubId().toString());
        triggerData.put("agentPubId", agent.getPubId().toString());
        triggerData.put("content", comment.getContent());
        boardTriggerService.fireTrigger(userPubId, board.getAgenticTeamId(),
                "trigger.board.task_comment_created", triggerData);

        return BoardTaskCommentResponse.from(comment, agent.getPubId());
    }

    // ---- Helpers ----

    private Board findBoardByPubId(UUID pubId) {
        return boardRepository.findByPubId(pubId)
                .orElseThrow(() -> new NotFoundStatusException("Board not found"));
    }

    private void validateBoardOwnership(Board board, UUID userPubId) {
        if (!board.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }
    }

    private Agent resolveTeamAgent(Board board, UUID agentPubId) {
        Agent agent = agentRepository.findByPubId(agentPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        if (agent.getAgenticTeamId() == null || !agent.getAgenticTeamId().equals(board.getAgenticTeamId())) {
            throw new BadRequestStatusException("Agent does not belong to the board's agentic team");
        }
        return agent;
    }

    private Map<Long, Agent> resolveAgentsForTasks(List<BoardTask> tasks) {
        Set<Long> agentIds = new HashSet<>();
        for (BoardTask task : tasks) {
            agentIds.add(task.getCreatedByAgentId());
            if (task.getAssigneeAgentId() != null) {
                agentIds.add(task.getAssigneeAgentId());
            }
        }
        return agentRepository.findAllById(agentIds).stream()
                .collect(Collectors.toMap(Agent::getId, Function.identity()));
    }

    private BoardTaskResponse toBoardTaskResponse(BoardTask task, Map<Long, Agent> agentsById, Map<Long, BoardTask> tasksById) {
        Agent createdBy = agentsById.get(task.getCreatedByAgentId());
        Agent assignee = task.getAssigneeAgentId() != null ? agentsById.get(task.getAssigneeAgentId()) : null;
        BoardTask parentTask = task.getParentTaskId() != null ? tasksById.get(task.getParentTaskId()) : null;

        return BoardTaskResponse.from(task,
                createdBy != null ? createdBy.getPubId() : null,
                assignee != null ? assignee.getPubId() : null,
                parentTask != null ? parentTask.getPubId() : null);
    }
}
