package ru.agimate.controlapi.connectors.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.dto.JobSpecification;
import ru.agimate.controlapi.connectors.core.events.ConnectorCreatedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorDeletedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorModifiedEvent;
import ru.agimate.controlapi.connectors.core.jobs.ConnectorJobService;
import ru.agimate.controlapi.database.enums.ConnectorJobType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectorIdentityListener")
class ConnectorIdentityListenerTest {

    private static final String IDENTITY = "integration-1";
    private static final UUID USER_ID = UUID.randomUUID();

    private static final JobSpecification SPEC_A = new JobSpecification(
            "test.task_a", ConnectorJobType.PERIODIC, Map.of("intervalSeconds", 0L), Map.of(), 60);
    private static final JobSpecification SPEC_B = new JobSpecification(
            "test.task_b", ConnectorJobType.CRON, Map.of("cron", "0 0 * * * *"), Map.of(), 300);

    @Mock
    private ConnectorHandler handler;

    @Mock
    private ConnectorJobService jobService;

    private ConnectorIdentityListener listener;

    @BeforeEach
    void setUp() {
        when(handler.connectorCode()).thenReturn("test");
        listener = new ConnectorIdentityListener(new ConnectorRegistry(List.of(handler)), jobService);
    }

    @Test
    @DisplayName("created: upsert всех задач из getJobs()")
    void onCreated() {
        when(handler.getJobs()).thenReturn(Map.of("test.task_a", SPEC_A, "test.task_b", SPEC_B));

        listener.onCreated(new ConnectorCreatedEvent("test", IDENTITY, USER_ID));

        verify(jobService).upsert("test", IDENTITY, USER_ID, SPEC_A);
        verify(jobService).upsert("test", IDENTITY, USER_ID, SPEC_B);
    }

    @Test
    @DisplayName("modified: пересинхронизация задач identity")
    void onModified() {
        when(handler.getJobs()).thenReturn(Map.of("test.task_a", SPEC_A));

        listener.onModified(new ConnectorModifiedEvent("test", IDENTITY, USER_ID));

        verify(jobService).syncIdentity(eq("test"), eq(IDENTITY), eq(USER_ID),
                argThat(specs -> specs.size() == 1 && specs.contains(SPEC_A)));
    }

    @Test
    @DisplayName("deleted: удаление всех задач identity")
    void onDeleted() {
        listener.onDeleted(new ConnectorDeletedEvent("test", IDENTITY));

        verify(jobService).deleteByIdentity("test", IDENTITY);
    }

    @Test
    @DisplayName("неизвестный коннектор: created/modified — warn и skip")
    void unknownConnectorSkipped() {
        listener.onCreated(new ConnectorCreatedEvent("ghost", IDENTITY, USER_ID));
        listener.onModified(new ConnectorModifiedEvent("ghost", IDENTITY, USER_ID));

        verifyNoInteractions(jobService);
    }
}
