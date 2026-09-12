package ru.agimate.controlapi.connectors.core.jobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.JobProvider;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.database.entities.ConnectorJob;
import ru.agimate.controlapi.database.enums.ConnectorJobKind;
import ru.agimate.controlapi.database.enums.ConnectorJobType;
import ru.agimate.controlapi.database.repositories.ConnectorJobRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectorJobService")
class ConnectorJobServiceTest {

    @Mock
    private ConnectorJobRepository repository;

    @InjectMocks
    private ConnectorJobService service;

    /** Джобы декларирует только коннектор с {@link JobProvider}. */
    interface JobCapableHandler extends ConnectorHandler, JobProvider {
    }

    private ConnectorRegistry registryOf(String connectorCode, JobCapableHandler handler) {
        when(handler.connectorCode()).thenReturn(connectorCode);
        return new ConnectorRegistry(List.of(handler));
    }

    private ConnectorJob systemRow(String connectorCode, String name) {
        return ConnectorJob.builder()
                .id(UUID.randomUUID())
                .connectorCode(connectorCode)
                .connectionId(UUID.randomUUID().toString())
                .kind(ConnectorJobKind.SYSTEM)
                .name(name)
                .type(ConnectorJobType.PERIODIC)
                .config(Map.of("intervalSeconds", 0L))
                .args(Map.of())
                .timeoutSeconds(60)
                .build();
    }

    @Nested
    @DisplayName("resyncSystemJobs")
    class ResyncSystemJobs {

        @Test
        @DisplayName("изменённая спека → точечный updateSpec с новыми значениями")
        void updatesChangedSpec() {
            ConnectorJob row = systemRow("telegram", "long_poll");
            when(repository.findByKind(ConnectorJobKind.SYSTEM)).thenReturn(List.of(row));
            JobSpec spec = new JobSpec("long_poll", ConnectorJobType.PERIODIC,
                    Map.of("intervalSeconds", 0L), Map.of(), 30);
            JobCapableHandler handler = mock(JobCapableHandler.class);
            when(handler.getJobs(any())).thenReturn(Map.of("long_poll", spec));

            service.resyncSystemJobs(registryOf("telegram", handler));

            verify(repository).updateSpec(row.getId(), spec.type(), spec.config(), spec.args(), 30);
            verify(repository, never()).deleteById(any());
        }

        @Test
        @DisplayName("имя больше не декларируется (смена режима) → строка удаляется")
        void deletesUndeclared() {
            ConnectorJob row = systemRow("telegram", "long_poll");
            when(repository.findByKind(ConnectorJobKind.SYSTEM)).thenReturn(List.of(row));

            JobCapableHandler handler = mock(JobCapableHandler.class);
            when(handler.getJobs(any())).thenReturn(Map.of());

            service.resyncSystemJobs(registryOf("telegram", handler));

            verify(repository).deleteById(row.getId());
            verify(repository, never()).updateSpec(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("декларация спрашивается у инстанса строки, не у коннектора вообще")
        void asksTheInstance() {
            ConnectorJob row = systemRow("mcp", "oauth_refresh");
            when(repository.findByKind(ConnectorJobKind.SYSTEM)).thenReturn(List.of(row));
            JobCapableHandler handler = mock(JobCapableHandler.class);
            when(handler.getJobs(any())).thenReturn(Map.of());

            service.resyncSystemJobs(registryOf("mcp", handler));

            verify(handler).getJobs(argThat(env -> row.getConnectionId().equals(env.connectionId())));
        }

        @Test
        @DisplayName("коннектор без handler'а в registry — строка не трогается")
        void skipsUnknownConnector() {
            ConnectorJob row = systemRow("ghost", "job");
            when(repository.findByKind(ConnectorJobKind.SYSTEM)).thenReturn(List.of(row));

            service.resyncSystemJobs(new ConnectorRegistry(List.of()));

            verify(repository, never()).deleteById(any());
            verify(repository, never()).updateSpec(any(), any(), any(), any(), any());
        }
    }
}
