package ru.agimate.controlapi.service.connection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import ru.agimate.controlapi.connectors.core.ConnectionCredentialStore;
import ru.agimate.controlapi.connectors.core.ConnectorEnvFactory;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.events.ConnectorDeletedEvent;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.service.AppService;
import ru.agimate.controlapi.service.secret.SecretService;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectionService — удаление подключения")
class ConnectionServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final UUID APP_ID = UUID.randomUUID();

    @Mock
    private ConnectionRepository connectionRepository;
    @Mock
    private ConnectorRepository connectorRepository;
    @Mock
    private ConnectorRegistry connectorRegistry;
    @Mock
    private ConnectorEnvFactory envFactory;
    @Mock
    private SecretService secretService;
    @Mock
    private ConnectionCredentialStore credentialStore;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AppService appService;

    @InjectMocks
    private ConnectionService service;

    @Test
    @DisplayName("APP-подключение удаляется через AppService — строка apps и навык не остаются")
    void appConnectionDelegatesToAppService() {
        Connection connection = Connection.builder()
                .id(CONNECTION_ID).userId(USER_ID).connectorCode("app").appId(APP_ID).build();
        when(connectionRepository.findByIdNotDeleted(CONNECTION_ID)).thenReturn(Optional.of(connection));

        service.delete(CONNECTION_ID, USER_ID);

        verify(appService).deleteApp(APP_ID, USER_ID);
        verify(connectionRepository, never()).softDelete(any(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("обычное подключение по-прежнему мягко удаляется общим путём")
    void integrationConnectionKeepsTheGenericPath() {
        Connection connection = Connection.builder()
                .id(CONNECTION_ID).userId(USER_ID).connectorCode("mcp").build();
        when(connectionRepository.findByIdNotDeleted(CONNECTION_ID)).thenReturn(Optional.of(connection));

        service.delete(CONNECTION_ID, USER_ID);

        verify(connectionRepository).softDelete(eq(CONNECTION_ID), any());
        verify(eventPublisher).publishEvent(any(ConnectorDeletedEvent.class));
        verifyNoInteractions(appService);
    }
}
