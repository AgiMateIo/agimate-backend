package ru.agimate.controlapi.connectors.internal.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.connectors.core.jobs.ConnectorJobService;
import ru.agimate.controlapi.database.entities.ConnectorJob;
import ru.agimate.controlapi.database.enums.ConnectorJobType;
import ru.agimate.controlapi.service.trigger.ChannelInfo;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("TimeToolService")
class TimeToolServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    private final ConnectorJobService jobService = mock(ConnectorJobService.class);
    private final TriggerRouterService triggerRouterService = mock(TriggerRouterService.class);
    private final TimeConnectorService handler =
            new TimeConnectorService(new TimeToolService(jobService, triggerRouterService));

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
        assertEquals("Выпей воды.", trigger.getValue().data().get("prompt"));
        Channels channels = trigger.getValue().context().channels();
        assertNull(channels.prompt());
        assertEquals(new ChannelInfo(channelId, sessionId, null), channels.progress());
        assertEquals(channels.progress(), channels.answer());
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
}
