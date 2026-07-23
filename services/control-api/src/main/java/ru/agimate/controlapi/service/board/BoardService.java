package ru.agimate.controlapi.service.board;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.*;
import ru.agimate.controlapi.database.enums.BoardTaskStatus;
import ru.agimate.controlapi.database.enums.BoardTaskType;
import ru.agimate.controlapi.database.repositories.*;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;
import ru.agimate.controlapi.service.dto.board.BoardCreateCommand;
import ru.agimate.controlapi.service.dto.board.BoardEventType;
import ru.agimate.controlapi.service.dto.board.BoardResponse;
import ru.agimate.controlapi.service.dto.board.BoardTaskCardResponse;
import ru.agimate.controlapi.service.dto.board.BoardTaskEditCommand;
import ru.agimate.controlapi.service.dto.board.BoardTaskUpdatedEvent;
import ru.agimate.controlapi.service.dto.board.BoardTaskCommentCreateCommand;
import ru.agimate.controlapi.service.dto.board.BoardTaskCommentCreatedEvent;
import ru.agimate.controlapi.service.dto.board.BoardTaskCommentResponse;
import ru.agimate.controlapi.service.dto.board.BoardTaskCreateCommand;
import ru.agimate.controlapi.service.dto.board.BoardTaskCreatedEvent;
import ru.agimate.controlapi.service.dto.board.BoardTaskResponse;
import ru.agimate.controlapi.service.dto.board.BoardTaskStatusChangeCommand;
import ru.agimate.controlapi.service.dto.board.BoardTaskStatusChangedEvent;
import ru.agimate.controlapi.service.dto.board.BoardTasksByStatusResponse;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerAudience;
import ru.agimate.controlapi.service.trigger.TriggerContext;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Доменный сервис досок (ядро). Общий для HTTP-управления ({@code ManageBoardController}) и
 * board-коннектора ({@code BoardToolService}); последний вызывает его как плагин поверх ядра и
 * транслирует {@link ru.agimate.common.rest.error.BaseHttpStatusException} в {@code ConnectorException}
 * на своей границе.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BoardService {

    /** Код board-коннектора; единый источник истины, {@code BoardConnectorService} ссылается сюда. */
    public static final String CONNECTOR_CODE = "board";

    /** Имена триггеров; декларации ({@code TriggerSpec}) — в {@code BoardConnectorService}. */
    public static final String TASK_CREATED_TRIGGER = "task_created";
    public static final String TASK_CHANGED_TRIGGER = "task_changed";

    private final BoardRepository boardRepository;
    private final BoardTaskRepository boardTaskRepository;
    private final BoardTaskCommentRepository boardTaskCommentRepository;
    private final AgenticTeamRepository agenticTeamRepository;
    private final AgentRepository agentRepository;
    private final ConnectionRepository connectionRepository;
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
    public BoardResponse create(UUID userId, BoardCreateCommand command) {
        AgenticTeam team = agenticTeamRepository.findById(command.agenticTeamId())
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
                .name(command.name())
                .description(command.description())
                .build();
        board = boardRepository.save(board);

        log.info("Created board '{}' for agenticTeam={}, user={}", command.name(), team.getId(), userId);
        return BoardResponse.from(board, team);
    }

    // ---- Tasks ----

    public BoardTasksByStatusResponse getTasksByStatus(UUID boardId, UUID userId) {
        return getTasksByStatus(boardId, userId, null, null);
    }

    /** Листинг с опциональными фильтрами; {@code null}-фильтр = без сужения. */
    public BoardTasksByStatusResponse getTasksByStatus(UUID boardId, UUID userId,
                                                       BoardTaskStatus statusFilter, UUID assigneeFilter) {
        Board board = findBoardById(boardId);
        validateBoardOwnership(board, userId);

        List<BoardTask> tasks = boardTaskRepository.findByBoardIdOrderByCreatedAtDesc(board.getId()).stream()
                .filter(t -> statusFilter == null || t.getStatus() == statusFilter)
                .filter(t -> assigneeFilter == null || assigneeFilter.equals(t.getAssigneeAgentId()))
                .toList();
        Map<UUID, Agent> agentsById = resolveAgentsForTasks(tasks);
        Map<UUID, BoardTask> tasksById = tasks.stream()
                .collect(Collectors.toMap(BoardTask::getId, Function.identity()));

        Map<BoardTaskStatus, List<BoardTaskResponse>> grouped = new LinkedHashMap<>();
        for (BoardTaskStatus status : BoardTaskStatus.values()) {
            if (statusFilter == null || status == statusFilter) {
                grouped.put(status, new ArrayList<>());
            }
        }

        for (BoardTask task : tasks) {
            BoardTaskResponse response = toBoardTaskResponse(task, agentsById, tasksById);
            grouped.get(task.getStatus()).add(response);
        }

        return new BoardTasksByStatusResponse(grouped);
    }

    /**
     * Карточка задачи: сама задача + вертикаль иерархии (ближайший EPIC, родитель, сабтаски)
     * + хвост комментариев (последние {@code 10}, хронологически). Точечная альтернатива
     * полной выгрузке доски.
     */
    public BoardTaskCardResponse getTaskCard(UUID boardId, UUID taskId, UUID userId) {
        BoardTask task = requireTaskInBoard(boardId, taskId);
        Board board = boardRepository.findById(task.getBoardId())
                .orElseThrow(() -> new NotFoundStatusException("Board not found"));
        validateBoardOwnership(board, userId);

        BoardTask parent = task.getParentTaskId() != null
                ? boardTaskRepository.findById(task.getParentTaskId()).orElse(null)
                : null;
        // Ближайший EPIC вверх: для TASK — родитель, для SUBTASK — родитель родителя (TASK → EPIC).
        BoardTask epic = null;
        if (parent != null) {
            epic = parent.getType() == BoardTaskType.EPIC ? parent
                    : parent.getParentTaskId() != null
                        ? boardTaskRepository.findById(parent.getParentTaskId()).orElse(null)
                        : null;
        }

        List<BoardTaskCardResponse.TaskRef> subtasks = boardTaskRepository.findByParentTaskId(task.getId())
                .stream()
                .sorted(Comparator.comparing(BoardTask::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(BoardTaskCardResponse.TaskRef::from)
                .toList();

        List<BoardTaskCommentResponse> comments = new ArrayList<>(boardTaskCommentRepository
                .findTop10ByBoardTaskIdOrderByCreatedAtDesc(task.getId()).stream()
                .map(c -> BoardTaskCommentResponse.from(c, c.getAgentId()))
                .toList());
        Collections.reverse(comments);

        return new BoardTaskCardResponse(
                BoardTaskResponse.from(task, task.getCreatedByAgentId(),
                        task.getAssigneeAgentId(), task.getParentTaskId()),
                epic != null && epic.getType() == BoardTaskType.EPIC
                        ? BoardTaskCardResponse.TaskRef.from(epic) : null,
                parent != null ? BoardTaskCardResponse.TaskRef.from(parent) : null,
                subtasks,
                comments);
    }

    @Transactional
    public BoardTaskResponse createTask(UUID boardId, UUID userId, BoardTaskCreateCommand command) {
        Board board = findBoardById(boardId);
        validateBoardOwnership(board, userId);

        Agent createdBy = resolveTeamAgent(board, command.createdByAgentId());
        Agent assignee = null;
        if (command.assigneeAgentId() != null) {
            assignee = resolveTeamAgent(board, command.assigneeAgentId());
        }

        if (command.type() == BoardTaskType.EPIC && command.parentTaskId() != null) {
            throw new BadRequestStatusException("EPIC tasks cannot have a parent");
        }
        if (command.type() == BoardTaskType.SUBTASK && command.parentTaskId() == null) {
            throw new BadRequestStatusException("SUBTASK must have a parent task");
        }

        UUID parentTaskId = null;
        BoardTask parentTask = null;
        if (command.parentTaskId() != null) {
            parentTask = boardTaskRepository.findById(command.parentTaskId())
                    .orElseThrow(() -> new NotFoundStatusException("Parent task not found"));
            if (!parentTask.getBoardId().equals(board.getId())) {
                throw new BadRequestStatusException("Parent task does not belong to this board");
            }
            if (command.type() == BoardTaskType.SUBTASK && parentTask.getType() != BoardTaskType.TASK) {
                throw new BadRequestStatusException("SUBTASK parent must be a TASK");
            }
            if (command.type() == BoardTaskType.TASK && parentTask.getType() != BoardTaskType.EPIC) {
                throw new BadRequestStatusException("TASK parent must be an EPIC");
            }
            parentTaskId = parentTask.getId();
        }

        BoardTask task = BoardTask.builder()
                .boardId(board.getId())
                .userId(userId)
                .parentTaskId(parentTaskId)
                .type(command.type())
                .title(command.title())
                .description(command.description())
                .createdByAgentId(createdBy.getId())
                .assigneeAgentId(assignee != null ? assignee.getId() : null)
                .build();
        task = boardTaskRepository.save(task);

        log.info("Created board task '{}' on board={}", command.title(), boardId);

        Map<String, Object> triggerData = new LinkedHashMap<>();
        triggerData.put("taskId", task.getId().toString());
        triggerData.put("createdByAgentId", createdBy.getId().toString());
        if (assignee != null) {
            triggerData.put("assigneeAgentId", assignee.getId().toString());
        }
        triggerData.put("type", task.getType().name());
        triggerData.put("title", task.getTitle());
        triggerData.put("description", task.getDescription());
        if (parentTask != null) {
            triggerData.put("parentTaskId", parentTaskId.toString());
            triggerData.put("parentTaskTitle", parentTask.getTitle());
        }

        // Без assignee адресат — ростер команды доски: сужение получателей до команды
        // делает только audience (см. routeBoardTrigger).
        List<UUID> targets = assignee != null
                ? List.of(assignee.getId())
                : teamRosterIds(userId, board);
        routeBoardTrigger(userId, board, TASK_CREATED_TRIGGER, triggerData,
                new TriggerAudience(createdBy.getId(), targets));

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

    /**
     * Загрузить задачу. Если {@code boardId} задан (REST-путь, вложенный в доску) — проверить
     * принадлежность задачи доске; {@code null} (агентский тул оперирует по taskId) — без проверки.
     */
    private BoardTask requireTaskInBoard(UUID boardId, UUID taskId) {
        BoardTask task = boardTaskRepository.findById(taskId)
                .orElseThrow(() -> new NotFoundStatusException("Task not found"));
        if (boardId != null && !task.getBoardId().equals(boardId)) {
            throw new NotFoundStatusException("Task not found");
        }
        return task;
    }

    @Transactional
    public BoardTaskResponse changeTaskStatus(UUID boardId, UUID taskId, UUID userId, BoardTaskStatusChangeCommand command) {
        return editTask(boardId, taskId, userId,
                new BoardTaskEditCommand(command.agentId(), null, null, null, command.status()));
    }

    /**
     * Частичная правка задачи ({@code null}-поле = «не менять»); поле, совпавшее с текущим
     * значением, изменением не считается — без изменений нет ни триггера, ни событий.
     *
     * <p>Assignee — claim-семантика: назначить можно только неназначенную задачу или переназначить
     * с себя; перехват чужой запрещён — при гонке «кто возьмёт» побеждает первый. Дискриминатор
     * триггера приоритетом: менялся статус → {@code change=status} (workflow-значимое событие,
     * фильтры по нему работают), иначе {@code change=edited}; полный список — {@code changedFields}.
     */
    @Transactional
    public BoardTaskResponse editTask(UUID boardId, UUID taskId, UUID userId, BoardTaskEditCommand command) {
        BoardTask task = requireTaskInBoard(boardId, taskId);
        Board board = boardRepository.findById(task.getBoardId())
                .orElseThrow(() -> new NotFoundStatusException("Board not found"));
        validateBoardOwnership(board, userId);
        Agent actor = resolveTeamAgent(board, command.actorAgentId());

        List<String> changedFields = new ArrayList<>();
        if (command.title() != null && !command.title().isBlank()
                && !command.title().equals(task.getTitle())) {
            task.setTitle(command.title());
            changedFields.add("title");
        }
        if (command.description() != null && !command.description().equals(task.getDescription())) {
            task.setDescription(command.description());
            changedFields.add("description");
        }
        UUID previousAssigneeId = task.getAssigneeAgentId();
        boolean assigneeChanged = false;
        if (command.assigneeAgentId() != null && !command.assigneeAgentId().equals(previousAssigneeId)) {
            if (previousAssigneeId != null && !previousAssigneeId.equals(actor.getId())) {
                throw new BadRequestStatusException("Task is already assigned to another agent");
            }
            resolveTeamAgent(board, command.assigneeAgentId());
            task.setAssigneeAgentId(command.assigneeAgentId());
            changedFields.add("assignee");
            assigneeChanged = true;
        }
        BoardTaskStatus previousStatus = task.getStatus();
        boolean statusChanged = command.status() != null && command.status() != previousStatus;
        if (statusChanged) {
            task.setStatus(command.status());
            changedFields.add("status");
        }

        Map<UUID, Agent> agentsById = resolveAgentsForTasks(List.of(task));
        if (changedFields.isEmpty()) {
            return toBoardTaskResponse(task, agentsById, parentById(task));
        }
        task = boardTaskRepository.save(task);
        log.info("Edited task {}: {}", taskId, changedFields);

        Map<String, Object> triggerData = taskSnapshot(task);
        triggerData.put("change", statusChanged ? "status" : "edited");
        if (statusChanged) {
            triggerData.put("previousStatus", previousStatus.name());
        }
        triggerData.put("changedFields", List.copyOf(changedFields));
        if (assigneeChanged && previousAssigneeId != null) {
            triggerData.put("previousAssigneeAgentId", previousAssigneeId.toString());
        }
        triggerData.put("actorAgentId", actor.getId().toString());

        routeBoardTrigger(userId, board, TASK_CHANGED_TRIGGER, triggerData,
                new TriggerAudience(actor.getId(), resolveTaskParticipantIds(task, agentsById)));

        if (statusChanged) {
            publishBoardEvent(userId, board.getId(), BoardEventType.TASK_STATUS_CHANGED,
                    new BoardTaskStatusChangedEvent(
                            board.getId(),
                            task.getId(),
                            previousStatus,
                            task.getStatus()
                    ));
        }
        if (changedFields.size() > (statusChanged ? 1 : 0)) {
            publishBoardEvent(userId, board.getId(), BoardEventType.TASK_UPDATED,
                    new BoardTaskUpdatedEvent(board.getId(), task.getId(), List.copyOf(changedFields)));
        }

        return toBoardTaskResponse(task, agentsById, parentById(task));
    }

    private Map<UUID, BoardTask> parentById(BoardTask task) {
        return task.getParentTaskId() != null
                ? boardTaskRepository.findById(task.getParentTaskId())
                    .map(p -> Map.of(p.getId(), p)).orElse(Map.<UUID, BoardTask>of())
                : Map.of();
    }

    // ---- Comments ----

    public List<BoardTaskCommentResponse> getComments(UUID boardId, UUID taskId, UUID userId) {
        BoardTask task = requireTaskInBoard(boardId, taskId);
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
    public BoardTaskCommentResponse createComment(UUID boardId, UUID taskId, UUID userId, BoardTaskCommentCreateCommand command) {
        BoardTask task = requireTaskInBoard(boardId, taskId);
        Board board = boardRepository.findById(task.getBoardId())
                .orElseThrow(() -> new NotFoundStatusException("Board not found"));
        validateBoardOwnership(board, userId);

        Agent agent = resolveTeamAgent(board, command.agentId());

        BoardTaskComment comment = BoardTaskComment.builder()
                .boardTaskId(task.getId())
                .userId(userId)
                .agentId(agent.getId())
                .content(command.content())
                .build();
        comment = boardTaskCommentRepository.save(comment);

        log.info("Created comment on task {} by agent {}", taskId, command.agentId());

        Map<String, Object> triggerData = taskSnapshot(task);
        triggerData.put("change", "comment");
        triggerData.put("commentId", comment.getId().toString());
        triggerData.put("comment", comment.getContent());
        triggerData.put("actorAgentId", agent.getId().toString());

        Map<UUID, Agent> agentsById = resolveAgentsForTasks(List.of(task));
        routeBoardTrigger(userId, board, TASK_CHANGED_TRIGGER, triggerData,
                new TriggerAudience(agent.getId(), resolveTaskParticipantIds(task, agentsById)));

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

    /**
     * Общий снапшот задачи в {@code data} триггера {@value #TASK_CHANGED_TRIGGER}: агент понимает,
     * о какой задаче речь, без {@code get_tasks}. Description намеренно не входит — объёмный текст
     * доступен тулами, событие остаётся компактным.
     */
    private static Map<String, Object> taskSnapshot(BoardTask task) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getId().toString());
        data.put("type", task.getType().name());
        data.put("title", task.getTitle());
        data.put("status", task.getStatus().name());
        if (task.getAssigneeAgentId() != null) {
            data.put("assigneeAgentId", task.getAssigneeAgentId().toString());
        }
        if (task.getParentTaskId() != null) {
            data.put("parentTaskId", task.getParentTaskId().toString());
        }
        return data;
    }

    /**
     * Эмиссия board-триггера. Connection-строка коннектора общая на пользователя (одна на все его
     * доски), поэтому сужение получателей до команды/участников — обязанность audience:
     * {@code targetAgentIds} обязаны быть заполнены каждым вызывающим. Нет connection — к доскам
     * не привязан ни один агент, доставлять некому.
     */
    private void routeBoardTrigger(UUID userId, Board board, String name,
                                   Map<String, Object> data, TriggerAudience audience) {
        Optional<Connection> connection = connectionRepository
                .findByUserIdAndConnectorCodeNotDeleted(userId, CONNECTOR_CODE).stream()
                .findFirst();
        if (connection.isEmpty()) {
            log.debug("No board connection for user {} — trigger '{}' not routed", userId, name);
            return;
        }
        data.put("boardId", board.getId().toString());
        Trigger trigger = Trigger.createDirected(
                CONNECTOR_CODE,
                connection.get().getId().toString(),
                name,
                data,
                TriggerContext.audience(audience)
        );
        triggerRouterService.routeTrigger(userId, trigger);
    }

    /** Ростер команды доски — широковещательный адресат (binding'и/ABAC сузят его в роутере). */
    private List<UUID> teamRosterIds(UUID userId, Board board) {
        return agentRepository.findByUserIdAndAgenticTeamId(userId, board.getAgenticTeam().getId()).stream()
                .map(Agent::getId)
                .toList();
    }

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
