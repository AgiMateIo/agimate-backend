package ru.agimate.controlapi.connectors.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.dto.TaskSpecification;
import ru.agimate.controlapi.connectors.core.events.ConnectorCreatedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorDeletedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorModifiedEvent;
import ru.agimate.controlapi.connectors.tasks.ConnectorTaskService;
import ru.agimate.controlapi.database.enums.ConnectorTaskType;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectorIdentityListener")
class ConnectorIdentityListenerTest {

    private static final String IDENTITY = "integration-1";

    private static final TaskSpecification SPEC_A = new TaskSpecification(
            "test.task_a", ConnectorTaskType.PERIODIC, Map.of("intervalSeconds", 0L), Map.of(), 60);
    private static final TaskSpecification SPEC_B = new TaskSpecification(
            "test.task_b", ConnectorTaskType.CRON, Map.of("cron", "0 0 * * * *"), Map.of(), 300);

    @Mock
    private ConnectorHandler handler;

    @Mock
    private ConnectorTaskService taskService;

    private ConnectorIdentityListener listener;

    @BeforeEach
    void setUp() {
        when(handler.connectorCode()).thenReturn("test");
        listener = new ConnectorIdentityListener(new ConnectorRegistry(List.of(handler)), taskService);
    }

    @Test
    @DisplayName("created: upsert всех задач из getTasks()")
    void onCreated() {
        when(handler.getTasks()).thenReturn(Map.of("test.task_a", SPEC_A, "test.task_b", SPEC_B));

        listener.onCreated(new ConnectorCreatedEvent("test", IDENTITY));

        verify(taskService).upsert("test", IDENTITY, SPEC_A);
        verify(taskService).upsert("test", IDENTITY, SPEC_B);
    }

    @Test
    @DisplayName("modified: пересинхронизация задач identity")
    void onModified() {
        when(handler.getTasks()).thenReturn(Map.of("test.task_a", SPEC_A));

        listener.onModified(new ConnectorModifiedEvent("test", IDENTITY));

        verify(taskService).syncIdentity(eq("test"), eq(IDENTITY),
                argThat(specs -> specs.size() == 1 && specs.contains(SPEC_A)));
    }

    @Test
    @DisplayName("deleted: удаление всех задач identity")
    void onDeleted() {
        listener.onDeleted(new ConnectorDeletedEvent("test", IDENTITY));

        verify(taskService).deleteByIdentity("test", IDENTITY);
    }

    @Test
    @DisplayName("неизвестный коннектор: created/modified — warn и skip")
    void unknownConnectorSkipped() {
        listener.onCreated(new ConnectorCreatedEvent("ghost", IDENTITY));
        listener.onModified(new ConnectorModifiedEvent("ghost", IDENTITY));

        verifyNoInteractions(taskService);
    }
}
