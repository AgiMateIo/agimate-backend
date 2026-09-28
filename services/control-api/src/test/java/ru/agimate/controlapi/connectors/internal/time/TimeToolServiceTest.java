package ru.agimate.controlapi.connectors.internal.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ConnectorSettingsService;
import ru.agimate.controlapi.connectors.core.OwnerRequestGuard;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.connectors.core.jobs.ConnectorJobService;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.ConnectorJob;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.enums.ConnectorJobType;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.service.trigger.ChannelsCodec;
import ru.agimate.controlapi.service.trigger.ChannelInfo;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("TimeToolService")
class TimeToolServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    private final ConnectorJobService jobService = mock(ConnectorJobService.class);
    private final TriggerRouterService triggerRouterService = mock(TriggerRouterService.class);
    private final AgentRunRepository agentRunRepository = mock(AgentRunRepository.class);
    private final AgentConnectionRepository agentConnectionRepository = mock(AgentConnectionRepository.class);
    private final ConnectorSettingsService settingsService = new ConnectorSettingsService(agentConnectionRepository);
    private final TimeConnectorService handler = new TimeConnectorService(
            new TimeToolService(jobService, triggerRouterService, agentRunRepository,
                    settingsService, new OwnerRequestGuard(agentRunRepository)),
            settingsService);

    private static ConnectorEnv env() {
        return new ConnectorEnv(null, USER_ID, AGENT_ID, null, null, null, Map.of(), null);
    }

    @Test
    @DisplayName("zero-values (0, \"\") опциональных режимов трактуются как отсутствие")
    void zeroValuesTreatedAsAbsent() {
        UUID jobId = UUID.randomUUID();
        when(jobService.schedule(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ConnectorJob.builder().id(jobId).build());

        // Так шлют аргументы OpenAI-shim модели: присутствуют все режимы, неиспользуемые — zero-values.
        Map<String, Object> args = new HashMap<>();
        args.put("prompt", "Выпей воды.");
        args.put("delaySeconds", 120);
        args.put("intervalSeconds", 0);
        args.put("cron", "");
        args.put("zone", "");

        Map<String, Object> result = handler.executeTool(env(), "schedule", args);

        assertEquals(jobId.toString(), result.get("id"));
        assertEquals(ConnectorJobType.ONETIME.name(), result.get("taskType"));
        ArgumentCaptor<JobSpec> spec = ArgumentCaptor.forClass(JobSpec.class);
        verify(jobService).schedule(eq(TimeConnectorService.CONNECTOR_CODE), any(),
                eq(USER_ID), eq(AGENT_ID), any(), any(), spec.capture(), any());
        assertEquals(ConnectorJobType.ONETIME, spec.getValue().type());
        assertEquals("Выпей воды.", spec.getValue().args().get("prompt"));
    }

    @Test
    @DisplayName("снимок канала и prompt-сессии вызова уезжает в строку job")
    void channelAndSessionSnapshotStored() {
        UUID channelId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(jobService.schedule(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ConnectorJob.builder().id(UUID.randomUUID()).build());
        ConnectorEnv env = new ConnectorEnv(null, USER_ID, AGENT_ID, null, channelId, sessionId, Map.of(), null);

        handler.executeTool(env, "schedule", Map.of("prompt", "п", "delaySeconds", 60));

        verify(jobService).schedule(eq(TimeConnectorService.CONNECTOR_CODE), any(), eq(USER_ID),
                eq(AGENT_ID), eq(channelId), eq(sessionId), any(JobSpec.class), any());
    }

    @Test
    @DisplayName("fire кладёт канал и сессию из строки job в проактивные progress/answer")
    void fireCarriesChannelAndSession() {
        UUID channelId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ConnectorEnv env = new ConnectorEnv(null, USER_ID, AGENT_ID, null, channelId, sessionId, Map.of(), null);

        handler.executeJob(env, "fire", Map.of("prompt", "Выпей воды."));

        ArgumentCaptor<Trigger> trigger = ArgumentCaptor.forClass(Trigger.class);
        verify(triggerRouterService).routeTrigger(eq(USER_ID), trigger.capture());
        String prompt = (String) trigger.getValue().data().get("prompt");
        assertTrue(prompt.startsWith("Fired at "));
        assertTrue(prompt.endsWith("Z[UTC].\n\nВыпей воды."));
        Channels channels = trigger.getValue().context().channels();
        assertNull(channels.prompt());
        assertEquals(new ChannelInfo(channelId, sessionId, null), channels.progress());
        assertEquals(channels.progress(), channels.answer());
    }

    @Test
    @DisplayName("адрес ответа из снимка вызвавшего рана уезжает в args job и возвращается в fire")
    void replyAddressRoundTrip() {
        UUID channelId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Map<String, Object> chat = Map.of("chatId", 4271);
        AgentRun run = AgentRun.builder().channels(ChannelsCodec.toMap(
                Channels.ofPrompt(new ChannelInfo(channelId, sessionId, null, chat)))).build();
        when(agentRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(jobService.schedule(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ConnectorJob.builder().id(UUID.randomUUID()).build());
        ConnectorEnv callEnv = new ConnectorEnv(null, USER_ID, AGENT_ID, runId, channelId, sessionId, Map.of(), null);

        handler.executeTool(callEnv, "schedule", Map.of("prompt", "п", "delaySeconds", 60));

        ArgumentCaptor<JobSpec> spec = ArgumentCaptor.forClass(JobSpec.class);
        verify(jobService).schedule(any(), any(), any(), any(), any(), any(), spec.capture(), any());
        assertEquals(chat, spec.getValue().args().get(TimeToolService.REPLY_ADDRESS));

        ConnectorEnv jobEnv = new ConnectorEnv(null, USER_ID, AGENT_ID, null, channelId, sessionId, Map.of(), null);
        handler.executeJob(jobEnv, "fire", spec.getValue().args());

        ArgumentCaptor<Trigger> trigger = ArgumentCaptor.forClass(Trigger.class);
        verify(triggerRouterService).routeTrigger(eq(USER_ID), trigger.capture());
        assertEquals(chat, trigger.getValue().context().channels().answer().address());
    }

    @Test
    @DisplayName("nextRunAt у агента без пояса — UTC со смещением и именем зоны")
    void nextRunAtCarriesOffset() {
        when(jobService.schedule(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ConnectorJob.builder().id(UUID.randomUUID()).build());

        Map<String, Object> result = handler.executeTool(env(), "schedule",
                Map.of("prompt", "п", "cron", "0 0 9 * * *", "zone", "Europe/Moscow"));

        // 09:00 по Москве — это 06:00 UTC, в какой бы момент ни шёл тест
        assertTrue(((String) result.get("nextRunAt")).endsWith("T06:00:00Z[UTC]"));
    }

    @Test
    @DisplayName("неизвестная зона — ошибка для агента, а не исключение времени")
    void invalidZoneRejected() {
        ConnectorException e = assertThrows(ConnectorException.class, () -> handler.executeTool(env(),
                "schedule", Map.of("prompt", "п", "cron", "0 0 9 * * *", "zone", "Mars/Olympus")));

        assertEquals("Invalid zone: Mars/Olympus", e.getMessage());
    }

    @Test
    @DisplayName("два осмысленных режима — по-прежнему ошибка")
    void twoRealModesRejected() {
        Map<String, Object> args = Map.of(
                "prompt", "п", "delaySeconds", 120, "intervalSeconds", 60);

        ConnectorException e = assertThrows(ConnectorException.class,
                () -> handler.executeTool(env(), "schedule", args));

        assertEquals("Provide exactly one of: delaySeconds, intervalSeconds, cron", e.getMessage());
    }

    @Test
    @DisplayName("все режимы zero-values — ошибка «ровно один», а не молчаливый выбор")
    void allZeroValuesRejected() {
        Map<String, Object> args = Map.of(
                "prompt", "п", "delaySeconds", 0, "intervalSeconds", 0, "cron", "");

        assertThrows(ConnectorException.class,
                () -> handler.executeTool(env(), "schedule", args));
    }

    @Test
    @DisplayName("cancel_task панели отменяет задачу этого агента, как cancel_scheduled")
    void panelCancelsAgentTask() {
        UUID taskId = UUID.randomUUID();
        when(jobService.cancel(TimeConnectorService.CONNECTOR_CODE, USER_ID, AGENT_ID, taskId)).thenReturn(true);

        Map<String, Object> result = handler.executeTool(env(), "cancel_task", Map.of("id", taskId.toString()));

        assertEquals(true, result.get("cancelled"));
        verify(jobService).cancel(TimeConnectorService.CONNECTOR_CODE, USER_ID, AGENT_ID, taskId);
    }

    @Test
    @DisplayName("cancel_task чужой или несуществующей задачи — ошибка")
    void panelCancelUnknownTask() {
        assertThrows(ConnectorException.class, () -> handler.executeTool(env(), "cancel_task",
                Map.of("id", UUID.randomUUID().toString())));
    }

    @Nested
    @DisplayName("пояс агента")
    class AgentZone {

        private static final UUID CONNECTION_ID = UUID.randomUUID();

        private AgentConnection binding;

        private ConnectorEnv env(UUID runId) {
            return new ConnectorEnv(CONNECTION_ID.toString(), USER_ID, AGENT_ID, runId, null, null, Map.of(), null);
        }

        private void bindWith(Map<String, Object> settings) {
            binding = AgentConnection.builder().agentId(AGENT_ID).connectionId(CONNECTION_ID)
                    .settings(new HashMap<>(settings)).build();
            when(agentConnectionRepository.findActiveBinding(AGENT_ID, CONNECTION_ID)).thenReturn(Optional.of(binding));
        }

        @Test
        @DisplayName("cron без zone считается в поясе агента, и пояс пишется в config задачи явно")
        void cronWithoutZoneTakesAgentZone() {
            bindWith(Map.of("timezone", "Europe/Moscow"));
            when(jobService.schedule(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(ConnectorJob.builder().id(UUID.randomUUID()).build());

            Map<String, Object> result = handler.executeTool(env(null), "schedule",
                    Map.of("prompt", "п", "cron", "0 0 9 * * *"));

            ArgumentCaptor<JobSpec> spec = ArgumentCaptor.forClass(JobSpec.class);
            verify(jobService).schedule(any(), any(), any(), any(), any(), any(), spec.capture(), any());
            assertEquals("Europe/Moscow", spec.getValue().config().get("zone"));
            assertTrue(((String) result.get("nextRunAt")).endsWith("T09:00:00+03:00[Europe/Moscow]"));
        }

        @Test
        @DisplayName("current_datetime отдаёт время в поясе агента")
        void currentDateTimeInAgentZone() {
            bindWith(Map.of("timezone", "Europe/Moscow"));

            Map<String, Object> result = handler.executeTool(env(null), "current_datetime", Map.of());

            assertEquals("Europe/Moscow", result.get("zone"));
            ZonedDateTime parsed = ZonedDateTime.parse((String) result.get("dateTime"));
            assertEquals("Europe/Moscow", parsed.getZone().getId());
        }

        @Test
        @DisplayName("fire пишет момент срабатывания в поясе агента")
        void fireStampsAgentZone() {
            bindWith(Map.of("timezone", "Europe/Moscow"));

            handler.executeJob(env(null), "fire", Map.of("prompt", "Выпей воды."));

            ArgumentCaptor<Trigger> trigger = ArgumentCaptor.forClass(Trigger.class);
            verify(triggerRouterService).routeTrigger(eq(USER_ID), trigger.capture());
            assertTrue(((String) trigger.getValue().data().get("prompt")).contains("+03:00[Europe/Moscow]."));
        }

        @Test
        @DisplayName("set_timezone в ране из webchat сохраняет пояс на привязке")
        void setTimezoneFromWebchat() {
            bindWith(Map.of());
            UUID runId = UUID.randomUUID();
            when(agentRunRepository.findTriggerConnectorCode(runId)).thenReturn(Optional.of("webchat"));

            Map<String, Object> result = handler.executeTool(env(runId), "set_timezone",
                    Map.of("timezone", "Asia/Tokyo"));

            assertEquals("Asia/Tokyo", result.get("timezone"));
            assertEquals(Map.of("timezone", "Asia/Tokyo"), binding.getSettings());
            verify(agentConnectionRepository).save(binding);
        }

        @Test
        @DisplayName("set_timezone в ране из чужого канала — отказ, привязка не тронута")
        void setTimezoneFromForeignChannelRefused() {
            bindWith(Map.of("timezone", "Europe/Moscow"));
            UUID runId = UUID.randomUUID();
            when(agentRunRepository.findTriggerConnectorCode(runId)).thenReturn(Optional.of("telegram"));

            ConnectorException e = assertThrows(ConnectorException.class, () -> handler.executeTool(env(runId),
                    "set_timezone", Map.of("timezone", "Asia/Tokyo")));

            assertTrue(e.getMessage().startsWith("Settings can only be changed at the owner's request"));
            assertEquals(Map.of("timezone", "Europe/Moscow"), binding.getSettings());
            verify(agentConnectionRepository, never()).save(any());
        }

        @Test
        @DisplayName("set_timezone без рана (ключ агента) разрешён")
        void setTimezoneWithoutRunAllowed() {
            bindWith(Map.of());

            handler.executeTool(env(null), "set_timezone", Map.of("timezone", "Asia/Tokyo"));

            assertEquals(Map.of("timezone", "Asia/Tokyo"), binding.getSettings());
        }

        @Test
        @DisplayName("set_timezone с пустым поясом — ошибка, а не молчаливый сброс")
        void setTimezoneBlankRejected() {
            bindWith(Map.of("timezone", "Europe/Moscow"));

            assertThrows(ConnectorException.class,
                    () -> handler.executeTool(env(null), "set_timezone", Map.of("timezone", "")));

            assertEquals(Map.of("timezone", "Europe/Moscow"), binding.getSettings());
        }

        @Test
        @DisplayName("save_settings панели не проверяет происхождение, пустой пояс сбрасывает на UTC")
        void panelSaveResets() {
            bindWith(Map.of("timezone", "Europe/Moscow"));

            Map<String, Object> result = handler.executeTool(env(null), "save_settings", Map.of("timezone", ""));

            assertNull(result.get("timezone"));
            assertEquals("UTC", result.get("zone"));
            assertEquals(Map.of(), binding.getSettings());
        }

        @Test
        @DisplayName("смещение вместо IANA-имени — ошибка: оно теряет летнее время")
        void offsetRejected() {
            bindWith(Map.of());

            ConnectorException e = assertThrows(ConnectorException.class, () -> handler.executeTool(env(null),
                    "save_settings", Map.of("timezone", "+03:00")));

            assertTrue(e.getMessage().contains("an offset loses daylight saving time"));
            assertFalse(binding.getSettings().containsKey("timezone"));
        }

        @Test
        @DisplayName("пояс без привязки — ошибка для агента")
        void unboundRejected() {
            ConnectorException e = assertThrows(ConnectorException.class, () -> handler.executeTool(env(null),
                    "save_settings", Map.of("timezone", "Europe/Moscow")));

            assertEquals("The connection is not bound to this agent", e.getMessage());
        }
    }
}
