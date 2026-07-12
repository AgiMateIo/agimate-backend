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
import ru.agimate.controlapi.database.entities.TriggerLogAgent;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.database.repositories.TriggerLogAgentRepository;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;
import ru.agimate.controlapi.service.trigger.ChannelInfo;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.ChannelsCodec;

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

    @Mock private TriggerLogAgentRepository triggerLogAgentRepository;
    @Mock private ChannelSessionMessageRepository messageRepository;
    @Mock private ChannelMessageOutboundService outboundService;
    @Mock private InboundTextResolver inboundTextResolver;

    private MessageLogService service;

    @BeforeEach
    void setUp() {
        service = new MessageLogService(
                new MessageLogPersistence(triggerLogAgentRepository, messageRepository, inboundTextResolver),
                outboundService);
    }

    private TriggerLogAgent run(UUID sessionId, Channels channels) {
        TriggerLogAgent run = TriggerLogAgent.builder()
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
        when(triggerLogAgentRepository.findById(TRIGGER_ID)).thenReturn(Optional.of(run));
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
            when(inboundTextResolver.resolve(eq(PROMPT_CHANNEL), any())).thenReturn(Optional.of("hi"));
            when(messageRepository.insertIgnoreConflict(any(), any(), any(), anyInt(),
                    anyString(), isNull(), anyString(), anyString())).thenReturn(1);

            var result = service.save(AGENT_ID, TRIGGER_ID, 0,
                    ChannelSessionMessageKind.INBOUND, null, "");

            assertFalse(result.duplicate());
            verify(messageRepository).insertIgnoreConflict(eq(SESSION_ID), eq(AGENT_ID), eq(TRIGGER_ID),
                    eq(0), eq("INBOUND"), isNull(), eq("hi"), contains("\"text\""));
            verifyNoInteractions(outboundService);
        }

        @Test
        @DisplayName("повтор (run_id, seq) → duplicate=true")
        void duplicate() {
            run(SESSION_ID, dialogueChannels());
            when(messageRepository.insertIgnoreConflict(any(), any(), any(), anyInt(),
                    anyString(), anyString(), anyString(), isNull())).thenReturn(0);

            var result = service.save(AGENT_ID, TRIGGER_ID, 3,
                    ChannelSessionMessageKind.PROGRESS, "TOOL_CALL", "🔧 get_tasks");

            assertTrue(result.duplicate());
        }

        @Test
        @DisplayName("ANSWER помечает весь ран completed")
        void answerCompletesRun() {
            run(SESSION_ID, dialogueChannels());
            when(messageRepository.insertIgnoreConflict(any(), any(), any(), anyInt(),
                    anyString(), isNull(), anyString(), isNull())).thenReturn(1);

            service.save(AGENT_ID, TRIGGER_ID, 5, ChannelSessionMessageKind.ANSWER, null, "done");

            verify(messageRepository).markRunCompleted(TRIGGER_ID);
        }

        @Test
        @DisplayName("direct-ран: ANSWER → result, ERROR → error, истории и доставки нет")
        void directRun() {
            TriggerLogAgent run = run(null, null);

            service.save(AGENT_ID, TRIGGER_ID, 1, ChannelSessionMessageKind.ANSWER, null, "done");
            assertEquals("done", run.getResult());

            service.save(AGENT_ID, TRIGGER_ID, 2, ChannelSessionMessageKind.ERROR, null, "boom");
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
                    anyString(), any(), anyString(), isNull())).thenReturn(1);
        }

        @Test
        @DisplayName("PROGRESS → progress-канал, stream=progress, детерминированный messageId")
        void progressRouting() {
            run(SESSION_ID, dialogueChannels());

            service.save(AGENT_ID, TRIGGER_ID, 1, ChannelSessionMessageKind.PROGRESS, "TEXT", "thinking...");
            service.save(AGENT_ID, TRIGGER_ID, 1, ChannelSessionMessageKind.PROGRESS, "TEXT", "thinking...");

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

            service.save(AGENT_ID, TRIGGER_ID, 4, ChannelSessionMessageKind.ANSWER, null, "done");

            verify(outboundService).send(eq(AGENT_ID), eq(PROMPT_CHANNEL), eq(SESSION_ID),
                    any(OutboundMessage.class), anyString(), eq("answer"), isNull());
        }

        @Test
        @DisplayName("PROGRESS без progress-канала — только история, доставки нет")
        void progressWithoutChannel() {
            run(SESSION_ID, new Channels(new ChannelInfo(PROMPT_CHANNEL, SESSION_ID, null), null, null));

            service.save(AGENT_ID, TRIGGER_ID, 2, ChannelSessionMessageKind.PROGRESS, "TEXT", "line");

            verify(outboundService, never()).send(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("сбой доставки (канал удалён mid-run) не роняет запись — history-only")
        void deliveryFailureDoesNotFailSave() {
            run(SESSION_ID, dialogueChannels());
            when(outboundService.send(any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new NotFoundStatusException("Channel not found"));

            var result = service.save(AGENT_ID, TRIGGER_ID, 4, ChannelSessionMessageKind.ANSWER, null, "done");

            assertFalse(result.duplicate());
            verify(messageRepository).insertIgnoreConflict(eq(SESSION_ID), eq(AGENT_ID), eq(TRIGGER_ID),
                    eq(4), eq("ANSWER"), isNull(), eq("done"), isNull());
            verify(messageRepository).markRunCompleted(TRIGGER_ID);
        }
    }

    @Nested
    @DisplayName("Статус рана (проекция потока SaveMessage)")
    class StatusProjection {

        @BeforeEach
        void stubInsert() {
            org.mockito.Mockito.lenient().when(messageRepository.insertIgnoreConflict(any(), any(), any(),
                    anyInt(), anyString(), any(), anyString(), any())).thenReturn(1);
        }

        @Test
        @DisplayName("INBOUND → RUNNING (+ last_activity_at), ANSWER → DONE, ERROR → FAILED")
        void transitions() {
            TriggerLogAgent run = run(SESSION_ID, dialogueChannels());
            when(inboundTextResolver.resolve(any(), any())).thenReturn(Optional.of("hi"));

            service.save(AGENT_ID, TRIGGER_ID, 0, ChannelSessionMessageKind.INBOUND, null, "");
            assertEquals(RunStatus.RUNNING, run.getStatus());
            assertNotNull(run.getLastActivityAt());

            service.save(AGENT_ID, TRIGGER_ID, 3, ChannelSessionMessageKind.ANSWER, null, "done");
            assertEquals(RunStatus.DONE, run.getStatus());
        }

        @Test
        @DisplayName("ERROR терминален; терминальный статус реплеем не откатывается")
        void terminalIsSticky() {
            TriggerLogAgent run = run(SESSION_ID, dialogueChannels());

            service.save(AGENT_ID, TRIGGER_ID, 2, ChannelSessionMessageKind.ERROR, null, "boom");
            assertEquals(RunStatus.FAILED, run.getStatus());

            // Реплей INBOUND после финиша не воскрешает ран.
            when(inboundTextResolver.resolve(any(), any())).thenReturn(Optional.of("hi"));
            service.save(AGENT_ID, TRIGGER_ID, 0, ChannelSessionMessageKind.INBOUND, null, "");
            assertEquals(RunStatus.FAILED, run.getStatus());
        }

        @Test
        @DisplayName("PROGRESS статус не меняет, но продлевает активность")
        void progressTouchesOnly() {
            TriggerLogAgent run = run(SESSION_ID, dialogueChannels());

            service.save(AGENT_ID, TRIGGER_ID, 1, ChannelSessionMessageKind.PROGRESS, "TEXT", "step");

            assertEquals(RunStatus.ENQUEUED, run.getStatus());
            assertNotNull(run.getLastActivityAt());
        }
    }

    @Test
    @DisplayName("ран чужого агента → BadRequest")
    void foreignRun() {
        run(SESSION_ID, dialogueChannels());
        assertThrows(BadRequestStatusException.class,
                () -> service.save(UUID.randomUUID(), TRIGGER_ID, 0,
                        ChannelSessionMessageKind.INBOUND, null, ""));
    }
}
