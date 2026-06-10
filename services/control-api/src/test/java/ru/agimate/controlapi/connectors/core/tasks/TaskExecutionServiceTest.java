package ru.agimate.controlapi.connectors.core.tasks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorContext;
import ru.agimate.controlapi.connectors.core.ConnectorContextFactory;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.integrations.IntegrationEncryptionService;
import ru.agimate.controlapi.database.entities.ConnectorTask;
import ru.agimate.controlapi.database.entities.IntegrationCredentials;
import ru.agimate.controlapi.database.enums.ConnectorTaskType;
import ru.agimate.controlapi.database.repositories.IntegrationCredentialsRepository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskExecutionService")
class TaskExecutionServiceTest {

    private static final UUID CREDENTIALS_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private IntegrationCredentialsRepository credentialsRepository;

    @Mock
    private IntegrationEncryptionService encryptionService;

    @Mock
    private IntegrationConnectorHandler integrationHandler;

    @Mock
    private InternalConnectorHandler internalHandler;

    private TaskExecutionService service;

    @BeforeEach
    void setUp() {
        // ConnectorRegistry и ConnectorContextFactory — настоящие: логика выбора контекста
        // по типу хендлера и есть предмет теста.
        when(integrationHandler.connectorCode()).thenReturn("telegram");
        when(internalHandler.connectorCode()).thenReturn("board");
        ConnectorRegistry registry = new ConnectorRegistry(java.util.List.of(integrationHandler, internalHandler));
        service = new TaskExecutionService(registry, credentialsRepository,
                new ConnectorContextFactory(encryptionService));
    }

    private ConnectorTask row(String connectorCode, String identity) {
        return ConnectorTask.builder()
                .id(UUID.randomUUID())
                .connectorCode(connectorCode)
                .identity(identity)
                .taskName("some.task")
                .taskType(ConnectorTaskType.PERIODIC)
                .taskArgs(Map.of("arg", "value"))
                .build();
    }

    @Test
    @DisplayName("integration: контекст со свежими расшифрованными credentials")
    void integrationHappyPath() {
        IntegrationCredentials credentials = IntegrationCredentials.builder()
                .id(CREDENTIALS_ID)
                .userId(USER_ID)
                .encryptedData("encrypted")
                .enabled(true)
                .build();
        when(credentialsRepository.findByIdNotDeleted(CREDENTIALS_ID)).thenReturn(Optional.of(credentials));
        when(encryptionService.decryptCredentials("encrypted")).thenReturn(Map.of("token", "t1"));

        service.executeTask(row("telegram", CREDENTIALS_ID.toString()));

        verify(integrationHandler).executeTask(
                argThat((ConnectorContext ctx) ->
                        CREDENTIALS_ID.toString().equals(ctx.identity())
                                && USER_ID.equals(ctx.userId())
                                && "t1".equals(ctx.credentials().get("token"))),
                eq("some.task"),
                eq(Map.of("arg", "value")));
    }

    @Test
    @DisplayName("integration: отсутствующие credentials → ConnectorException")
    void integrationMissingCredentials() {
        when(credentialsRepository.findByIdNotDeleted(CREDENTIALS_ID)).thenReturn(Optional.empty());

        ConnectorException e = assertThrows(ConnectorException.class,
                () -> service.executeTask(row("telegram", CREDENTIALS_ID.toString())));

        assertTrue(e.getMessage().contains("missing or disabled"));
    }

    @Test
    @DisplayName("integration: выключенные credentials → ConnectorException")
    void integrationDisabledCredentials() {
        IntegrationCredentials disabled = IntegrationCredentials.builder()
                .id(CREDENTIALS_ID)
                .enabled(false)
                .build();
        when(credentialsRepository.findByIdNotDeleted(CREDENTIALS_ID)).thenReturn(Optional.of(disabled));

        assertThrows(ConnectorException.class,
                () -> service.executeTask(row("telegram", CREDENTIALS_ID.toString())));
    }

    @Test
    @DisplayName("integration: некорректный identity → ConnectorException")
    void integrationInvalidIdentity() {
        assertThrows(ConnectorException.class,
                () -> service.executeTask(row("telegram", "not-a-uuid")));
    }

    @Test
    @DisplayName("internal: голый контекст с identity строки")
    void internalContext() {
        service.executeTask(row("board", null));

        verify(internalHandler).executeTask(
                argThat((ConnectorContext ctx) ->
                        ctx.identity() == null && ctx.userId() == null && ctx.credentials().isEmpty()),
                eq("some.task"),
                eq(Map.of("arg", "value")));
    }

    @Test
    @DisplayName("неизвестный коннектор → ConnectorException")
    void unknownConnector() {
        assertThrows(ConnectorException.class,
                () -> service.executeTask(row("unknown", null)));
    }

    @Test
    @DisplayName("null taskArgs нормализуются в пустую мапу")
    void nullArgsNormalized() {
        ConnectorTask row = row("board", null);
        row.setTaskArgs(null);
        when(internalHandler.executeTask(any(), any(), any())).thenReturn(Map.of());

        service.executeTask(row);

        verify(internalHandler).executeTask(any(), eq("some.task"), eq(Map.of()));
    }
}
