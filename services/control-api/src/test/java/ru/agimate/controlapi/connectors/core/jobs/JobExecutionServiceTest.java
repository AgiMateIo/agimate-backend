package ru.agimate.controlapi.connectors.core.jobs;

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
import ru.agimate.controlapi.connectors.core.JobProvider;
import ru.agimate.controlapi.service.secret.SecretService;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.ConnectorJob;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.enums.ConnectorJobType;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.SecretRepository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("JobExecutionService")
class JobExecutionServiceTest {

    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final UUID SECRET_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private ConnectionRepository connectionRepository;

    @Mock
    private SecretRepository secretRepository;

    @Mock
    private SecretService secretService;

    interface IntegrationJobHandler extends IntegrationConnectorHandler, JobProvider {
    }

    interface InternalJobHandler extends InternalConnectorHandler, JobProvider {
    }

    @Mock
    private IntegrationJobHandler integrationHandler;

    @Mock
    private InternalJobHandler internalHandler;

    private JobExecutionService service;

    @BeforeEach
    void setUp() {
        // ConnectorRegistry и ConnectorContextFactory — настоящие: логика выбора контекста
        // по типу хендлера и есть предмет теста.
        when(integrationHandler.connectorCode()).thenReturn("telegram");
        when(internalHandler.connectorCode()).thenReturn("board");
        ConnectorRegistry registry = new ConnectorRegistry(java.util.List.of(integrationHandler, internalHandler));
        service = new JobExecutionService(registry, connectionRepository,
                new ConnectorContextFactory(secretRepository, secretService));
    }

    private ConnectorJob row(String connectorCode, String connectionId) {
        return ConnectorJob.builder()
                .id(UUID.randomUUID())
                .connectorCode(connectorCode)
                .connectionId(connectionId)
                .name("some.task")
                .type(ConnectorJobType.PERIODIC)
                .args(Map.of("arg", "value"))
                .build();
    }

    @Test
    @DisplayName("integration: контекст со свежими расшифрованными credentials")
    void integrationHappyPath() {
        Connection connection = Connection.builder()
                .id(CONNECTION_ID)
                .userId(USER_ID)
                .secretId(SECRET_ID)
                .enabled(true)
                .build();
        Secret secret = Secret.builder().id(SECRET_ID).entity("connection").build();
        when(connectionRepository.findByIdNotDeleted(CONNECTION_ID)).thenReturn(Optional.of(connection));
        when(secretRepository.findById(SECRET_ID)).thenReturn(Optional.of(secret));
        when(secretService.reveal(secret, CONNECTION_ID)).thenReturn(Map.of("token", "t1"));

        service.executeJob(row("telegram", CONNECTION_ID.toString()));

        verify(integrationHandler).executeJob(
                argThat((ConnectorContext ctx) ->
                        CONNECTION_ID.toString().equals(ctx.connectionId())
                                && USER_ID.equals(ctx.userId())
                                && "t1".equals(ctx.credentials().get("token"))),
                eq("some.task"),
                eq(Map.of("arg", "value")));
    }

    @Test
    @DisplayName("integration: отсутствующий connection → ConnectorException")
    void integrationMissingConnection() {
        when(connectionRepository.findByIdNotDeleted(CONNECTION_ID)).thenReturn(Optional.empty());

        ConnectorException e = assertThrows(ConnectorException.class,
                () -> service.executeJob(row("telegram", CONNECTION_ID.toString())));

        assertTrue(e.getMessage().contains("missing or disabled"));
    }

    @Test
    @DisplayName("integration: выключенный connection → ConnectorException")
    void integrationDisabledConnection() {
        Connection disabled = Connection.builder()
                .id(CONNECTION_ID)
                .enabled(false)
                .build();
        when(connectionRepository.findByIdNotDeleted(CONNECTION_ID)).thenReturn(Optional.of(disabled));

        assertThrows(ConnectorException.class,
                () -> service.executeJob(row("telegram", CONNECTION_ID.toString())));
    }

    @Test
    @DisplayName("integration: некорректный connectionId → ConnectorException")
    void integrationInvalidIdentity() {
        assertThrows(ConnectorException.class,
                () -> service.executeJob(row("telegram", "not-a-uuid")));
    }

    @Test
    @DisplayName("internal: голый контекст с connectionId строки")
    void internalContext() {
        service.executeJob(row("board", null));

        verify(internalHandler).executeJob(
                argThat((ConnectorContext ctx) ->
                        ctx.connectionId() == null && ctx.userId() == null && ctx.credentials().isEmpty()),
                eq("some.task"),
                eq(Map.of("arg", "value")));
    }

    @Test
    @DisplayName("internal: userId/agentId строки реконструируются в контекст")
    void internalContextReconstructsOwner() {
        UUID agentId = UUID.randomUUID();
        ConnectorJob row = row("board", null);
        row.setUserId(USER_ID);
        row.setAgentId(agentId);

        service.executeJob(row);

        verify(internalHandler).executeJob(
                argThat((ConnectorContext ctx) ->
                        USER_ID.equals(ctx.userId()) && agentId.equals(ctx.agentId())),
                eq("some.task"),
                eq(Map.of("arg", "value")));
    }

    @Test
    @DisplayName("неизвестный коннектор → ConnectorException")
    void unknownConnector() {
        assertThrows(ConnectorException.class,
                () -> service.executeJob(row("unknown", null)));
    }

    @Test
    @DisplayName("null args нормализуются в пустую мапу")
    void nullArgsNormalized() {
        ConnectorJob row = row("board", null);
        row.setArgs(null);
        when(internalHandler.executeJob(any(), any(), any())).thenReturn(Map.of());

        service.executeJob(row);

        verify(internalHandler).executeJob(any(), eq("some.task"), eq(Map.of()));
    }
}
