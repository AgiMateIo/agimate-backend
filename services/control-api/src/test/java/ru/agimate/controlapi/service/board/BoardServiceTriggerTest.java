package ru.agimate.controlapi.service.board;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgenticTeam;
import ru.agimate.controlapi.database.entities.Board;
import ru.agimate.controlapi.database.entities.BoardTask;
import ru.agimate.controlapi.database.entities.BoardTaskComment;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.enums.BoardTaskType;
import ru.agimate.controlapi.database.repositories.*;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;
import ru.agimate.controlapi.service.dto.board.BoardTaskCommentCreateCommand;
import ru.agimate.controlapi.service.dto.board.BoardTaskCreateCommand;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Эмиссия board-триггеров: connection-строка общая на пользователя, сужение получателей —
 * только через audience, поэтому targets обязаны быть заполнены всегда.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BoardService — эмиссия триггеров")
class BoardServiceTriggerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TEAM_ID = UUID.randomUUID();
    private static final UUID BOARD_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final UUID LEAD_ID = UUID.randomUUID();
    private static final UUID WORKER_ID = UUID.randomUUID();

    @Mock private BoardRepository boardRepository;
    @Mock private BoardTaskRepository boardTaskRepository;
    @Mock private BoardTaskCommentRepository boardTaskCommentRepository;
    @Mock private AgenticTeamRepository agenticTeamRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private ConnectionRepository connectionRepository;
    @Mock private TriggerRouterService triggerRouterService;
    @Mock private CentrifugoService centrifugoService;

    @InjectMocks
    private BoardService service;

    private Board board;
    private Agent lead;
    private Agent worker;

    @BeforeEach
    void setUp() {
        AgenticTeam team = AgenticTeam.builder().id(TEAM_ID).userId(USER_ID).build();
        board = Board.builder().id(BOARD_ID).userId(USER_ID).agenticTeam(team).name("b").build();
        lead = Agent.builder().id(LEAD_ID).userId(USER_ID).agenticTeamId(TEAM_ID).name("lead").build();
        worker = Agent.builder().id(WORKER_ID).userId(USER_ID).agenticTeamId(TEAM_ID).name("worker").build();

        lenient().when(boardRepository.findById(BOARD_ID)).thenReturn(Optional.of(board));
        lenient().when(agentRepository.findById(LEAD_ID)).thenReturn(Optional.of(lead));
        lenient().when(agentRepository.findById(WORKER_ID)).thenReturn(Optional.of(worker));
        lenient().when(agentRepository.findAllById(any())).thenReturn(List.of(lead, worker));
        lenient().when(boardTaskRepository.save(any())).thenAnswer(inv -> {
            BoardTask t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });
        lenient().when(boardTaskCommentRepository.save(any())).thenAnswer(inv -> {
            BoardTaskComment c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
    }

    private void modeConnectionExists() {
        when(connectionRepository.findByUserIdAndConnectorCodeNotDeleted(USER_ID, "board"))
                .thenReturn(List.of(Connection.builder().id(CONNECTION_ID).userId(USER_ID)
                        .connectorCode("board").build()));
    }

    private Trigger routedTrigger() {
        ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
        verify(triggerRouterService).routeTrigger(eq(USER_ID), captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("createTask")
    class CreateTask {

        @Test
        @DisplayName("с assignee: connectionId = строка-режим, actor = createdBy, target = assignee, boardId в data")
        void withAssignee() {
            modeConnectionExists();

            service.createTask(BOARD_ID, USER_ID, new BoardTaskCreateCommand(
                    BoardTaskType.TASK, "t", "d", LEAD_ID, WORKER_ID, null));

            Trigger trigger = routedTrigger();
            assertEquals(CONNECTION_ID.toString(), trigger.connectionId());
            assertEquals("task_created", trigger.name());
            assertEquals(BOARD_ID.toString(), trigger.data().get("boardId"));
            assertEquals(LEAD_ID, trigger.context().audience().actorAgentId());
            assertEquals(List.of(WORKER_ID), trigger.context().audience().targetAgentIds());
        }

        @Test
        @DisplayName("без assignee: targets = ростер команды (никогда не пустые)")
        void withoutAssigneeTargetsRoster() {
            modeConnectionExists();
            when(agentRepository.findByUserIdAndAgenticTeamId(USER_ID, TEAM_ID))
                    .thenReturn(List.of(lead, worker));

            service.createTask(BOARD_ID, USER_ID, new BoardTaskCreateCommand(
                    BoardTaskType.TASK, "t", null, LEAD_ID, null, null));

            Trigger trigger = routedTrigger();
            assertEquals(List.of(LEAD_ID, WORKER_ID), trigger.context().audience().targetAgentIds());
        }

        @Test
        @DisplayName("нет connection-строки board (никто не привязан) — триггер не эмитится")
        void noConnectionNoTrigger() {
            when(connectionRepository.findByUserIdAndConnectorCodeNotDeleted(USER_ID, "board"))
                    .thenReturn(List.of());

            service.createTask(BOARD_ID, USER_ID, new BoardTaskCreateCommand(
                    BoardTaskType.TASK, "t", null, LEAD_ID, WORKER_ID, null));

            verify(triggerRouterService, never()).routeTrigger(any(), any());
        }
    }

    @Nested
    @DisplayName("createComment")
    class CreateComment {

        @Test
        @DisplayName("actor = автор коммента, targets = участники задачи, boardId в data")
        void commentTargetsParticipants() {
            modeConnectionExists();
            BoardTask task = BoardTask.builder()
                    .id(UUID.randomUUID())
                    .boardId(BOARD_ID)
                    .userId(USER_ID)
                    .type(BoardTaskType.TASK)
                    .title("t")
                    .createdByAgentId(LEAD_ID)
                    .assigneeAgentId(WORKER_ID)
                    .build();
            when(boardTaskRepository.findById(task.getId())).thenReturn(Optional.of(task));

            service.createComment(BOARD_ID, task.getId(), USER_ID,
                    new BoardTaskCommentCreateCommand(WORKER_ID, "done"));

            Trigger trigger = routedTrigger();
            assertEquals(CONNECTION_ID.toString(), trigger.connectionId());
            assertEquals("task_comment_created", trigger.name());
            assertEquals(BOARD_ID.toString(), trigger.data().get("boardId"));
            assertEquals(WORKER_ID, trigger.context().audience().actorAgentId());
            // Участники: createdBy + assignee; актор (worker) отфильтруется в TriggerAudience.filter.
            assertTrue(trigger.context().audience().targetAgentIds().containsAll(List.of(LEAD_ID, WORKER_ID)));
        }
    }
}
