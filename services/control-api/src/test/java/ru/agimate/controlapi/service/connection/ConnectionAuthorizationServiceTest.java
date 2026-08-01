package ru.agimate.controlapi.service.connection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.events.ConnectorCreatedEvent;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.McpOAuthService;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.enums.ConnectionAuthStatus;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectionAuthorizationService")
class ConnectionAuthorizationServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final String STATE = "state-1";

    @Mock
    private ConnectionRepository connectionRepository;
    @Mock
    private ConnectionService connectionService;
    @Mock
    private McpOAuthService oauthService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ConnectionAuthorizationService service;
    private Connection connection;

    @BeforeEach
    void setUp() {
        service = new ConnectionAuthorizationService(
                connectionRepository, connectionService, oauthService, eventPublisher);
        connection = Connection.builder()
                .id(CONNECTION_ID)
                .connectorCode("mcp")
                .userId(USER_ID)
                .authStatus(ConnectionAuthStatus.PENDING_AUTH)
                .oauthState(STATE)
                .oauthStateExpiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
    }

    private void stateFound() {
        when(connectionRepository.findByOauthStateAndUserId(STATE, USER_ID))
                .thenReturn(Optional.of(connection));
    }

    @Nested
    @DisplayName("завершение флоу")
    class Complete {

        @Test
        @DisplayName("чужой или несуществующий state — 404 без подробностей")
        void unknownState() {
            when(connectionRepository.findByOauthStateAndUserId(STATE, USER_ID))
                    .thenReturn(Optional.empty());

            assertThrows(NotFoundStatusException.class,
                    () -> service.complete(USER_ID, STATE, "code", null, null));
        }

        @Test
        @DisplayName("истёкший state — начинать заново")
        void expiredState() {
            connection.setOauthStateExpiresAt(LocalDateTime.now().minusMinutes(1));
            stateFound();

            assertThrows(BadRequestStatusException.class,
                    () -> service.complete(USER_ID, STATE, "code", null, null));
            verify(connectionRepository, never()).burnOauthState(any(), any());
        }

        @Test
        @DisplayName("state уже погашен параллельным завершением — второй проигрывает")
        void doubleCompletion() {
            stateFound();
            when(connectionRepository.burnOauthState(CONNECTION_ID, STATE)).thenReturn(0);

            assertThrows(BadRequestStatusException.class,
                    () -> service.complete(USER_ID, STATE, "code", null, null));
            verify(oauthService, never()).completeAuthorization(any(), any(), any());
        }

        @Test
        @DisplayName("успех: обмен кода, событие о готовности коннекции")
        void success() {
            stateFound();
            when(connectionRepository.burnOauthState(CONNECTION_ID, STATE)).thenReturn(1);

            Connection result = service.complete(USER_ID, STATE, "code", null, "https://auth.example.com");

            assertEquals(CONNECTION_ID, result.getId());
            verify(oauthService).completeAuthorization(connection, "code", "https://auth.example.com");
            verify(eventPublisher).publishEvent(any(ConnectorCreatedEvent.class));
        }

        @Test
        @DisplayName("отказ пользователя: state погашен, коннекция осталась неавторизованной")
        void accessDenied() {
            stateFound();
            when(connectionRepository.burnOauthState(CONNECTION_ID, STATE)).thenReturn(1);

            BadRequestStatusException e = assertThrows(BadRequestStatusException.class,
                    () -> service.complete(USER_ID, STATE, null, "access_denied", null));

            assertTrue(e.getMessage().contains("access_denied"));
            verify(oauthService).verifyIssuer(connection, null);
            verify(oauthService, never()).completeAuthorization(any(), any(), any());
            verify(eventPublisher, never()).publishEvent(any(ConnectorCreatedEvent.class));
        }

        @Test
        @DisplayName("iss не сошёлся на ответе с ошибкой — текст сервера авторизации не показываем")
        void issuerMismatchOnError() {
            stateFound();
            when(connectionRepository.burnOauthState(CONNECTION_ID, STATE)).thenReturn(1);
            org.mockito.Mockito.doThrow(new ConnectorException("The authorization response came from a "
                            + "different issuer"))
                    .when(oauthService).verifyIssuer(eq(connection), any());

            BadRequestStatusException e = assertThrows(BadRequestStatusException.class,
                    () -> service.complete(USER_ID, STATE, null, "access_denied", "https://evil.example"));

            assertTrue(e.getMessage().contains("different issuer"));
            assertTrue(!e.getMessage().contains("access_denied"));
        }

        @Test
        @DisplayName("ни кода, ни ошибки — нечего завершать")
        void neitherCodeNorError() {
            stateFound();
            when(connectionRepository.burnOauthState(CONNECTION_ID, STATE)).thenReturn(1);

            assertThrows(BadRequestStatusException.class,
                    () -> service.complete(USER_ID, STATE, null, null, null));
        }
    }

    @Nested
    @DisplayName("старт авторизации")
    class Start {

        @Test
        @DisplayName("коннектор без OAuth — отказ на границе, до коннекторного слоя")
        void unsupportedConnector() {
            Connection telegram = Connection.builder()
                    .id(CONNECTION_ID).connectorCode("telegram").userId(USER_ID).build();
            when(connectionService.getOwnedConnection(CONNECTION_ID, USER_ID)).thenReturn(telegram);

            assertThrows(BadRequestStatusException.class,
                    () -> service.startAuthorization(CONNECTION_ID, USER_ID));
        }

        @Test
        @DisplayName("ошибка коннекторного слоя переводится в 400, а не всплывает как есть")
        void connectorFailureBecomesBadRequest() {
            when(connectionService.getOwnedConnection(CONNECTION_ID, USER_ID)).thenReturn(connection);
            when(oauthService.startAuthorization(connection))
                    .thenThrow(new ConnectorException("OAuth is not configured on this installation"));

            BadRequestStatusException e = assertThrows(BadRequestStatusException.class,
                    () -> service.startAuthorization(CONNECTION_ID, USER_ID));
            assertTrue(e.getMessage().contains("not configured"));
        }
    }
}
