package ru.agimate.controlapi.service.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.TriggerLog;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.service.AgentRunTurnService;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;
import ru.agimate.controlapi.service.dto.ToolTurnRecord;
import ru.agimate.controlapi.service.trigger.ChannelInfo;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.ChannelsCodec;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import ru.agimate.common.rest.error.NotFoundStatusException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MessageLogService")
class MessageLogServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID TRIGGER_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID PROMPT_CHANNEL = UUID.randomUUID();
    private static final UUID PROGRESS_CHANNEL = UUID.randomUUID();

    @Mock private AgentRunRepository agentRunRepository;
    @Mock private ChannelSessionMessageRepository messageRepository;
    @Mock private ChannelMessageOutboundService outboundService;
    @Mock private InboundTextResolver inboundTextResolver;
    @Mock private AgentRunTurnService turnService;

    private MessageLogService service;

    @BeforeEach
    void setUp() {
        service = new MessageLogService(
                new MessageLogPersistence(agentRunRepository, messageRepository, inboundTextResolver, turnService),
                outboundService);
    }

    private AgentRun run(UUID sessionId, Channels channels) {
        AgentRun run = AgentRun.builder()
                .agent(Agent.builder().id(AGENT_ID).build())
                .triggerLog(TriggerLog.builder()
                        .connectorCode("webchat")
                        .connectionId(UUID.randomUUID().toString())
                        .externalId("evt-1")
                        .name("message_received")
                        .input(Map.of("text", "hi"))
                        .build())
                .destination("GENERIC")
                .sessionId(sessionId)
                .channels(ChannelsCodec.toMap(channels))
                .build();
        run.setId(TRIGGER_ID);
        when(agentRunRepository.findById(TRIGGER_ID)).thenReturn(Optional.of(run));
        return run;
    }

    private static Channels dialogueChannels() {
        ChannelInfo prompt = new ChannelInfo(PROMPT_CHANNEL, SESSION_ID, null);
        ChannelInfo progress = new ChannelInfo(PROGRESS_CHANNEL, SESSION_ID, null);
        return new Channels(prompt, progress, null);
    }

    @Nested
    @DisplayName("Запись")
    class Persistence {

        @Test
        @DisplayName("INBOUND: каноника из канала, trigger_input из trigger_log, доставки нет")
        void inboundAck() {
            run(SESSION_ID, dialogueChannels());
            when(inboundTextResolver.resolveText(eq(PROMPT_CHANNEL), any())).thenReturn(Optional.of("hi"));
            when(messageRepository.insertIgnoreConflict(any(), any(), any(), anyInt(),
                    anyString(), isNull(), anyString(), isNull(), anyString())).thenReturn(1);

            var result = service.save(AGENT_ID, TRIGGER_ID, 0,
                    ChannelSessionMessageKind.INBOUND, null, "", null);

            assertFalse(result.duplicate());
            verify(messageRepository).insertIgnoreConflict(eq(SESSION_ID), eq(AGENT_ID), eq(TRIGGER_ID),
                    eq(0), eq("INBOUND"), isNull(), eq("hi"), isNull(), contains("\"text\""));
            verifyNoInteractions(outboundService);
        }

        @Test
        @DisplayName("повтор (run_id, seq) → duplicate=true")
        void duplicate() {
            run(SESSION_ID, dialogueChannels());
            when(messageRepository.insertIgnoreConflict(any(), any(), any(), anyInt(),
                    anyString(), anyString(), anyString(), isNull(), isNull())).thenReturn(0);

            var result = service.save(AGENT_ID, TRIGGER_ID, 3,
                    ChannelSessionMessageKind.PROGRESS, "TOOL_CALL", "🔧 get_tasks", null);

            assertTrue(result.duplicate());
        }

        @Test
        @DisplayName("канальный ANSWER: помечает ран completed И пишет result на ран (самодостаточная строка)")
        void answerCompletesRun() {
            AgentRun run = run(SESSION_ID, dialogueChannels());
            when(messageRepository.insertIgnoreConflict(any(), any(), any(), anyInt(),
                    anyString(), isNull(), anyString(), isNull(), isNull())).thenReturn(1);

            service.save(AGENT_ID, TRIGGER_ID, 5, ChannelSessionMessageKind.ANSWER, null, "done", null);

            verify(messageRepository).markRunCompleted(TRIGGER_ID);
            assertEquals("done", run.getResult());
        }

        @Test
        @DisplayName("ANSWER: журнал ходов проверяется один раз, результат остаётся на ране")
        void answerStampsLedgerVerdict() {
            AgentRun run = run(SESSION_ID, dialogueChannels());
            when(messageRepository.insertIgnoreConflict(any(), any(), any(), anyInt(),
                    anyString(), isNull(), anyString(), isNull(), isNull())).thenReturn(1);
            when(turnService.isLedgerIntact(TRIGGER_ID)).thenReturn(false);

            service.save(AGENT_ID, TRIGGER_ID, 5, ChannelSessionMessageKind.ANSWER, null, "done", null);

            assertFalse(run.isTurnsIntact());
        }

        @Test
        @DisplayName("канальный ERROR: error пишется на ран (не только в историю)")
        void channelErrorRecordsOnRun() {
            AgentRun run = run(SESSION_ID, dialogueChannels());
            when(messageRepository.insertIgnoreConflict(any(), any(), any(), anyInt(),
                    anyString(), isNull(), anyString(), isNull(), isNull())).thenReturn(1);

            service.save(AGENT_ID, TRIGGER_ID, 6, ChannelSessionMessageKind.ERROR, null, "boom", null);

            assertEquals("boom", run.getError());
        }

        @Test
        @DisplayName("TOOL_CALL с toolTurn → message_json со структурной записью")
        void toolTurnPersisted() {
            run(SESSION_ID, dialogueChannels());
            when(messageRepository.insertIgnoreConflict(any(), any(), any(), anyInt(),
                    anyString(), anyString(), anyString(), anyString(), isNull())).thenReturn(1);

            var turn = new ToolTurnRecord("смотрю задачи",
                    List.of(new ToolTurnRecord.Call("c1", "board.get_tasks", "{\"boardId\":1}")),
                    List.of(new ToolTurnRecord.Result("c1", "board.get_tasks", "{\"tasks\":[]}", false)));
            service.save(AGENT_ID, TRIGGER_ID, 2,
                    ChannelSessionMessageKind.PROGRESS, "TOOL_CALL", "🔧 get_tasks", turn);

            var jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(messageRepository).insertIgnoreConflict(eq(SESSION_ID), eq(AGENT_ID), eq(TRIGGER_ID),
                    eq(2), eq("PROGRESS"), eq("TOOL_CALL"), eq("🔧 get_tasks"),
                    jsonCaptor.capture(), isNull());
            assertTrue(jsonCaptor.getValue().contains("\"board.get_tasks\""));
            assertTrue(jsonCaptor.getValue().contains("\"argumentsJson\""));
        }

        @Test
        @DisplayName("гигантский output капается при записи с маркером truncated")
        void toolTurnOutputCapped() {
            run(SESSION_ID, dialogueChannels());
            when(messageRepository.insertIgnoreConflict(any(), any(), any(), anyInt(),
                    anyString(), anyString(), anyString(), anyString(), isNull())).thenReturn(1);

            String huge = "x".repeat(MessageLogPersistence.TOOL_JSON_WRITE_CAP + 100);
            var turn = new ToolTurnRecord(null,
                    List.of(new ToolTurnRecord.Call("c1", "t", "{}")),
                    List.of(new ToolTurnRecord.Result("c1", "t", huge, false)));
            service.save(AGENT_ID, TRIGGER_ID, 2,
                    ChannelSessionMessageKind.PROGRESS, "TOOL_CALL", "🔧 t", turn);

            var jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(messageRepository).insertIgnoreConflict(any(), any(), any(), anyInt(),
                    anyString(), anyString(), anyString(), jsonCaptor.capture(), isNull());
            assertTrue(jsonCaptor.getValue().contains("…[truncated]"));
            assertFalse(jsonCaptor.getValue().contains(huge));
        }

        @Test
        @DisplayName("ран без каналов: ANSWER → result, ERROR → error; сессия есть, но проекции нет")
        void runWithoutChannels() {
            // Сессия коннекшена у такого рана есть всегда — признак «канальный» даёт снапшот каналов,
            // а не её наличие; иначе события коннекторов поехали бы в историю чата.
            AgentRun run = run(SESSION_ID, null);

            service.save(AGENT_ID, TRIGGER_ID, 1, ChannelSessionMessageKind.ANSWER, null, "done", null);
            assertEquals("done", run.getResult());

            service.save(AGENT_ID, TRIGGER_ID, 2, ChannelSessionMessageKind.ERROR, null, "boom", null);
            assertEquals("boom", run.getError());

            verifyNoInteractions(messageRepository);
            verifyNoInteractions(outboundService);
        }
    }

    @Nested
    @DisplayName("Доставка (проекция записи)")
    class Delivery {

        @BeforeEach
        void stubInsert() {
            when(messageRepository.insertIgnoreConflict(any(), any(), any(), anyInt(),
                    anyString(), any(), anyString(), any(), isNull())).thenReturn(1);
        }

        @Test
        @DisplayName("PROGRESS → progress-канал, stream=progress, детерминированный messageId")
        void progressRouting() {
            run(SESSION_ID, dialogueChannels());

            service.save(AGENT_ID, TRIGGER_ID, 1, ChannelSessionMessageKind.PROGRESS, "TEXT", "thinking...", null);
            service.save(AGENT_ID, TRIGGER_ID, 1, ChannelSessionMessageKind.PROGRESS, "TEXT", "thinking...", null);

            // Оба вызова (ретрай) шлют один и тот же messageId — дедуп downstream.
            verify(outboundService, org.mockito.Mockito.times(2)).send(eq(AGENT_ID), eq(PROGRESS_CHANNEL),
                    eq(SESSION_ID), any(OutboundMessage.class),
                    eq(UUID.nameUUIDFromBytes(("agimate-msglog:" + TRIGGER_ID + ":1")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()),
                    eq("progress"), eq("TEXT"));
        }

        @Test
        @DisplayName("ANSWER без answer-канала фолбэчится на prompt")
        void answerFallsBackToPrompt() {
            run(SESSION_ID, dialogueChannels());

            service.save(AGENT_ID, TRIGGER_ID, 4, ChannelSessionMessageKind.ANSWER, null, "done", null);

            verify(outboundService).send(eq(AGENT_ID), eq(PROMPT_CHANNEL), eq(SESSION_ID),
                    any(OutboundMessage.class), anyString(), eq("answer"), isNull());
        }

        @Test
        @DisplayName("PROGRESS без progress-канала — только история, доставки нет")
        void progressWithoutChannel() {
            run(SESSION_ID, new Channels(new ChannelInfo(PROMPT_CHANNEL, SESSION_ID, null), null, null));

            service.save(AGENT_ID, TRIGGER_ID, 2, ChannelSessionMessageKind.PROGRESS, "TEXT", "line", null);

            verify(outboundService, never()).send(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("сбой доставки (канал удалён mid-run) не роняет запись — history-only")
        void deliveryFailureDoesNotFailSave() {
            run(SESSION_ID, dialogueChannels());
            when(outboundService.send(any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new NotFoundStatusException("Channel not found"));

            var result = service.save(AGENT_ID, TRIGGER_ID, 4, ChannelSessionMessageKind.ANSWER, null, "done", null);

            assertFalse(result.duplicate());
            verify(messageRepository).insertIgnoreConflict(eq(SESSION_ID), eq(AGENT_ID), eq(TRIGGER_ID),
                    eq(4), eq("ANSWER"), isNull(), eq("done"), isNull(), isNull());
            verify(messageRepository).markRunCompleted(TRIGGER_ID);
        }
    }

    @Nested
    @DisplayName("Статус рана (проекция потока SaveMessage)")
    class StatusProjection {

        @BeforeEach
        void stubInsert() {
            org.mockito.Mockito.lenient().when(messageRepository.insertIgnoreConflict(any(), any(), any(),
                    anyInt(), anyString(), any(), anyString(), any(), any())).thenReturn(1);
        }

        @Test
        @DisplayName("INBOUND → RUNNING (+ last_activity_at), ANSWER → DONE, ERROR → FAILED")
        void transitions() {
            AgentRun run = run(SESSION_ID, dialogueChannels());
            when(inboundTextResolver.resolveText(any(), any())).thenReturn(Optional.of("hi"));

            service.save(AGENT_ID, TRIGGER_ID, 0, ChannelSessionMessageKind.INBOUND, null, "", null);
            assertEquals(RunStatus.RUNNING, run.getStatus());
            assertNotNull(run.getLastActivityAt());

            service.save(AGENT_ID, TRIGGER_ID, 3, ChannelSessionMessageKind.ANSWER, null, "done", null);
            assertEquals(RunStatus.DONE, run.getStatus());
        }

        @Test
        @DisplayName("ERROR терминален; терминальный статус реплеем не откатывается")
        void terminalIsSticky() {
            AgentRun run = run(SESSION_ID, dialogueChannels());

            service.save(AGENT_ID, TRIGGER_ID, 2, ChannelSessionMessageKind.ERROR, null, "boom", null);
            assertEquals(RunStatus.FAILED, run.getStatus());

            // Реплей INBOUND после финиша не воскрешает ран.
            when(inboundTextResolver.resolveText(any(), any())).thenReturn(Optional.of("hi"));
            service.save(AGENT_ID, TRIGGER_ID, 0, ChannelSessionMessageKind.INBOUND, null, "", null);
            assertEquals(RunStatus.FAILED, run.getStatus());
        }

        @Test
        @DisplayName("PROGRESS статус не меняет, но продлевает активность")
        void progressTouchesOnly() {
            AgentRun run = run(SESSION_ID, dialogueChannels());

            service.save(AGENT_ID, TRIGGER_ID, 1, ChannelSessionMessageKind.PROGRESS, "TEXT", "step", null);

            assertEquals(RunStatus.ENQUEUED, run.getStatus());
            assertNotNull(run.getLastActivityAt());
        }
    }

    @Nested
    @DisplayName("отмена")
    class Cancellation {

        @Test
        @DisplayName("терминальный ANSWER при запрошенной отмене → CANCELLED, не DONE")
        void answerAfterCancelRequestLandsCancelled() {
            AgentRun run = run(SESSION_ID, dialogueChannels());
            run.setStatus(RunStatus.RUNNING);
            run.setCancelRequestedAt(LocalDateTime.now());

            service.save(AGENT_ID, TRIGGER_ID, 3, ChannelSessionMessageKind.ANSWER, null, "остановлено", null);

            assertEquals(RunStatus.CANCELLED, run.getStatus());
            // Сообщения рана всё равно помечаются завершёнными — иначе отменённый ран не увидит история.
            verify(messageRepository).markRunCompleted(TRIGGER_ID);
        }

        @Test
        @DisplayName("отменённый в очереди ран терминален уже на своём ack — дальше записей не будет")
        void queuedRunIsTerminalAtItsAck() {
            AgentRun run = run(SESSION_ID, dialogueChannels());
            run.setCancelRequestedAt(LocalDateTime.now());

            service.save(AGENT_ID, TRIGGER_ID, 0, ChannelSessionMessageKind.INBOUND, null, "", null);

            assertEquals(RunStatus.CANCELLED, run.getStatus());
        }

        @Test
        @DisplayName("ран успел закончиться сам → DONE, гонка разрешается по факту")
        void answerWithoutCancelRequestLandsDone() {
            AgentRun run = run(SESSION_ID, dialogueChannels());
            run.setStatus(RunStatus.RUNNING);

            service.save(AGENT_ID, TRIGGER_ID, 3, ChannelSessionMessageKind.ANSWER, null, "готово", null);

            assertEquals(RunStatus.DONE, run.getStatus());
        }

        @Test
        @DisplayName("флаг отмены уезжает воркеру в ответе SaveMessage — это и есть весь транспорт сигнала")
        void cancellationRidesBackOnTheAnswer() {
            AgentRun run = run(SESSION_ID, dialogueChannels());
            run.setCancelRequestedAt(LocalDateTime.now());

            MessageLogService.SaveResult result = service.save(AGENT_ID, TRIGGER_ID, 0,
                    ChannelSessionMessageKind.INBOUND, null, "", null);

            assertTrue(result.cancelled());
        }

        @Test
        @DisplayName("без запроса отмены флаг не поднят")
        void noFlagWithoutRequest() {
            run(SESSION_ID, dialogueChannels());

            MessageLogService.SaveResult result = service.save(AGENT_ID, TRIGGER_ID, 0,
                    ChannelSessionMessageKind.INBOUND, null, "", null);

            assertFalse(result.cancelled());
        }
    }

    @Nested
    @DisplayName("стиринг (решение на seq 0)")
    class Steering {

        private final UUID mainRunId = UUID.randomUUID();

        private AgentRun steeredRun(RunStatus mainStatus, boolean confirmed) {
            AgentRun run = run(SESSION_ID, dialogueChannels());
            run.setMainRunId(mainRunId);
            if (confirmed) {
                run.setSteeredAt(LocalDateTime.now());
            }
            if (mainStatus != null) {
                when(agentRunRepository.findStatusById(mainRunId)).thenReturn(Optional.of(mainStatus));
            }
            return run;
        }

        @Test
        @DisplayName("поглощение подтверждено, main DONE → STEERED, флаг уезжает воркеру")
        void confirmedWithDoneMainStandsAside() {
            AgentRun run = steeredRun(RunStatus.DONE, true);

            MessageLogService.SaveResult result = service.save(AGENT_ID, TRIGGER_ID, 0,
                    ChannelSessionMessageKind.INBOUND, null, "", null);

            assertTrue(result.steered());
            assertEquals(RunStatus.STEERED, run.getStatus());
        }

        @Test
        @DisplayName("main CANCELLED — стоп накрыл и поглощённое сообщение → STEERED")
        void confirmedWithCancelledMainStandsAside() {
            AgentRun run = steeredRun(RunStatus.CANCELLED, true);

            MessageLogService.SaveResult result = service.save(AGENT_ID, TRIGGER_ID, 0,
                    ChannelSessionMessageKind.INBOUND, null, "", null);

            assertTrue(result.steered());
            assertEquals(RunStatus.STEERED, run.getStatus());
        }

        @Test
        @DisplayName("main FAILED — ответа не было, ран работает как обычно")
        void failedMainMeansTheRunExecutes() {
            AgentRun run = steeredRun(RunStatus.FAILED, true);

            MessageLogService.SaveResult result = service.save(AGENT_ID, TRIGGER_ID, 0,
                    ChannelSessionMessageKind.INBOUND, null, "", null);

            assertFalse(result.steered());
            assertEquals(RunStatus.RUNNING, run.getStatus());
        }

        @Test
        @DisplayName("захват без подтверждения (ответ ClaimSteering потерян) → ран работает, сообщение не теряется")
        void unconfirmedClaimMeansTheRunExecutes() {
            AgentRun run = steeredRun(null, false);

            MessageLogService.SaveResult result = service.save(AGENT_ID, TRIGGER_ID, 0,
                    ChannelSessionMessageKind.INBOUND, null, "", null);

            assertFalse(result.steered());
            assertEquals(RunStatus.RUNNING, run.getStatus());
        }

        @Test
        @DisplayName("отмена сильнее стиринга: оба факта → CANCELLED")
        void cancellationWinsOverSteering() {
            AgentRun run = steeredRun(RunStatus.DONE, true);
            run.setCancelRequestedAt(LocalDateTime.now());

            MessageLogService.SaveResult result = service.save(AGENT_ID, TRIGGER_ID, 0,
                    ChannelSessionMessageKind.INBOUND, null, "", null);

            assertTrue(result.cancelled());
            assertEquals(RunStatus.CANCELLED, run.getStatus());
        }

        @Test
        @DisplayName("STEERED терминален: реплей ack'а статус не откатывает")
        void steeredIsSticky() {
            AgentRun run = run(SESSION_ID, dialogueChannels());
            run.setStatus(RunStatus.STEERED);

            service.save(AGENT_ID, TRIGGER_ID, 0, ChannelSessionMessageKind.INBOUND, null, "", null);

            assertEquals(RunStatus.STEERED, run.getStatus());
        }
    }

    @Test
    @DisplayName("ран чужого агента → BadRequest")
    void foreignRun() {
        run(SESSION_ID, dialogueChannels());
        assertThrows(BadRequestStatusException.class,
                () -> service.save(UUID.randomUUID(), TRIGGER_ID, 0,
                        ChannelSessionMessageKind.INBOUND, null, "", null));
    }
}
