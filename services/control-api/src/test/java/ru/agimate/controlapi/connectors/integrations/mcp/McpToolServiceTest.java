package ru.agimate.controlapi.connectors.integrations.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.service.secret.SecretService;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.ConnectionTool;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.SecretRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpToolService")
class McpToolServiceTest {

    private static final UUID IDENTITY = UUID.randomUUID();
    private static final UUID SECRET_ID = UUID.randomUUID();

    @Mock
    private ConnectionRepository connectionRepository;
    @Mock
    private SecretRepository secretRepository;
    @Mock
    private SecretService secretService;
    @Mock
    private McpClient mcpClient;
    @Mock
    private ConnectionToolRepository connectionToolRepository;

    private McpToolService service;

    @BeforeEach
    void setUp() {
        service = new McpToolService(connectionRepository, secretRepository, secretService,
                mcpClient, connectionToolRepository);
    }

    private static JsonNode rawTool(String name) {
        return JsonUtils.toJsonNode("{\"name\":\"" + name + "\",\"description\":\"" + name + " desc\"}");
    }

    private static ConnectionTool cached(String name) {
        return ConnectionTool.builder().connectionId(IDENTITY).name(name).build();
    }

    @Nested
    @DisplayName("discover")
    class Discover {

        @Test
        @DisplayName("MCP-экземпляр: снимает tools/list и мапит в строки кэша")
        void discoversMcpTools() {
            Connection connection = Connection.builder()
                    .id(IDENTITY)
                    .connectorCode(McpConnectorService.CONNECTOR_CODE)
                    .secretId(SECRET_ID)
                    .build();
            Secret secret = Secret.builder().id(SECRET_ID).entity("connection").build();
            when(connectionRepository.findByIdNotDeleted(IDENTITY)).thenReturn(Optional.of(connection));
            when(secretRepository.findById(SECRET_ID)).thenReturn(Optional.of(secret));
            when(secretService.reveal(secret, IDENTITY))
                    .thenReturn(Map.of(McpUtils.FIELD_URL, "https://srv/mcp"));
            when(mcpClient.listTools(any())).thenReturn(List.of(rawTool("a"), rawTool("b")));

            List<ConnectionTool> fresh = service.discover(IDENTITY);

            assertEquals(2, fresh.size());
            assertEquals("a", fresh.get(0).getName());
        }

        @Test
        @DisplayName("не MCP-коннектор: null, без обращения к серверу")
        void skipsNonMcp() {
            when(connectionRepository.findByIdNotDeleted(IDENTITY)).thenReturn(Optional.of(
                    Connection.builder().id(IDENTITY).connectorCode("telegram").build()));

            assertNull(service.discover(IDENTITY));
            verifyNoInteractions(mcpClient);
        }

        @Test
        @DisplayName("экземпляр не найден: null")
        void missingConnection() {
            when(connectionRepository.findByIdNotDeleted(IDENTITY)).thenReturn(Optional.empty());
            assertNull(service.discover(IDENTITY));
        }
    }

    @Nested
    @DisplayName("reconcile")
    class Reconcile {

        @Test
        @DisplayName("upsert новых/изменённых тулов + удаление пропавших")
        void upsertsAndDeletesStale() {
            when(connectionToolRepository.findActiveByConnectionId(IDENTITY))
                    .thenReturn(List.of(cached("a"), cached("stale")));

            service.reconcile(IDENTITY, List.of(cached("a"), cached("b")));

            // a — обновлён (существовал), b — вставлен (новый)
            verify(connectionToolRepository).save(argThat(t -> t.getName().equals("a")));
            verify(connectionToolRepository).save(argThat(t -> t.getName().equals("b")));
            // stale — пропал из tools/list → удалён
            verify(connectionToolRepository).delete(argThat(t -> t.getName().equals("stale")));
        }

        @Test
        @DisplayName("пустой tools/list: всё существующее удаляется")
        void emptyListRemovesAll() {
            when(connectionToolRepository.findActiveByConnectionId(IDENTITY))
                    .thenReturn(List.of(cached("a")));

            service.reconcile(IDENTITY, List.of());

            verify(connectionToolRepository).delete(argThat(t -> t.getName().equals("a")));
            verify(connectionToolRepository, never()).save(any());
        }
    }
}
