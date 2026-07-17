package ru.agimate.controlapi.connectors.core.jobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.database.entities.ConnectorJob;
import ru.agimate.controlapi.database.enums.ConnectorJobKind;
import ru.agimate.controlapi.database.enums.ConnectorJobType;
import ru.agimate.controlapi.database.repositories.ConnectorJobRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
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

    private ConnectorJob systemRow(String connectorCode, String name) {
        return ConnectorJob.builder()
                .id(UUID.randomUUID())
                .connectorCode(connectorCode)
                .connectionId("conn-1")
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

            service.resyncSystemJobs(Map.of("telegram", Map.of("long_poll", spec)));

            verify(repository).updateSpec(row.getId(), spec.type(), spec.config(), spec.args(), 30);
            verify(repository, never()).deleteById(any());
        }

        @Test
        @DisplayName("имя больше не декларируется (смена режима) → строка удаляется")
        void deletesUndeclared() {
            ConnectorJob row = systemRow("telegram", "long_poll");
            when(repository.findByKind(ConnectorJobKind.SYSTEM)).thenReturn(List.of(row));

            service.resyncSystemJobs(Map.of("telegram", Map.of()));

            verify(repository).deleteById(row.getId());
            verify(repository, never()).updateSpec(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("коннектор без handler'а в registry — строка не трогается")
        void skipsUnknownConnector() {
            ConnectorJob row = systemRow("ghost", "job");
            when(repository.findByKind(ConnectorJobKind.SYSTEM)).thenReturn(List.of(row));

            service.resyncSystemJobs(Map.of());

            verify(repository, never()).deleteById(any());
            verify(repository, never()).updateSpec(any(), any(), any(), any(), any());
        }
    }
}
