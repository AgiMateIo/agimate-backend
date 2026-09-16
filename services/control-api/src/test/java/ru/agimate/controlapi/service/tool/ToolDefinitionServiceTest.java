package ru.agimate.controlapi.service.tool;

import ru.agimate.controlapi.connectors.core.dto.ToolAudience;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvFactory;
import ru.agimate.controlapi.connectors.core.ConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.ToolProvider;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.ConnectionTool;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.enums.DefinitionBinding;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@DisplayName("ToolDefinitionService — единственный источник тулов коннекции")
class ToolDefinitionServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();

    private final ConnectorRepository connectorRepository = mock(ConnectorRepository.class);
    private final ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
    private final ConnectionToolRepository connectionToolRepository = mock(ConnectionToolRepository.class);
    private final ConnectorHandler staticHandler =
            mock(ConnectorHandler.class, withSettings().extraInterfaces(ToolProvider.class));

    private ToolDefinitionService service;

    @BeforeEach
    void setUp() {
        when(staticHandler.connectorCode()).thenReturn("time");
        connector("time", DefinitionBinding.STATIC);
        connector("mcp", DefinitionBinding.DYNAMIC);
        connector("webchat", null);
        when(connectionToolRepository.findActiveByConnectionId(CONNECTION_ID)).thenReturn(List.of(
                ConnectionTool.builder().connectionId(CONNECTION_ID).name("search").build()));
        service = new ToolDefinitionService(connectorRepository, new ConnectorRegistry(List.of(staticHandler)),
                connectionRepository, connectionToolRepository);
    }

    private void connector(String code, DefinitionBinding binding) {
        Connector connector = new Connector();
        connector.setCode(code);
        connector.setDefinitionBinding(binding);
        when(connectorRepository.findById(code)).thenReturn(Optional.of(connector));
    }

    private static Connection connection(String connectorCode) {
        return Connection.builder().id(CONNECTION_ID).connectorCode(connectorCode).build();
    }

    @Nested
    @DisplayName("по уже найденной коннекции")
    class HeldConnection {

        @Test
        @DisplayName("DYNAMIC: кэш connection_tools, без повторной проверки владельца")
        void dynamicReadsCache() {
            Map<String, ConnectorToolSpec> tools =
                    service.getTools(connection("mcp"), ConnectorEnvFactory.listing(CONNECTION_ID), ToolAudience.ALL);

            assertEquals(List.of("search"), List.copyOf(tools.keySet()));
            verify(connectionRepository, never()).findByIdAndUserIdNotDeleted(any(), any());
        }

        @Test
        @DisplayName("STATIC: провайдер получает env вызывающего — сессионный env рана не подменяется листинговым")
        void staticGetsCallersEnv() {
            ConnectorEnv sessionEnv = new ConnectorEnv(CONNECTION_ID.toString(), null, null, null, null,
                    UUID.randomUUID(), Map.of(), null);
            Map<String, ConnectorToolSpec> expected = Map.of("schedule",
                    new ConnectorToolSpec("schedule", null, null, null, null, null, null, null));
            when(((ToolProvider) staticHandler).getTools(sessionEnv)).thenReturn(expected);

            assertSame(expected, service.getTools(connection("time"), sessionEnv, ToolAudience.ALL));
        }

        @Test
        @DisplayName("коннектор без определений тулов (канал) — пусто, а не ошибка")
        void channelConnectorIsEmpty() {
            assertTrue(service.getTools(connection("webchat"), ConnectorEnvFactory.listing(CONNECTION_ID), ToolAudience.ALL).isEmpty());
        }
    }

    @Test
    @DisplayName("аудитория режет в источнике: тул только для вью не виден модели, но есть в ALL")
    void audienceFiltersAtTheSource() {
        when(connectionToolRepository.findActiveByConnectionId(CONNECTION_ID)).thenReturn(List.of(
                ConnectionTool.builder().connectionId(CONNECTION_ID).name("search").build(),
                ConnectionTool.builder().connectionId(CONNECTION_ID).name("refresh")
                        .meta("{\"ui\":{\"visibility\":[\"app\"]}}").build()));
        ConnectorEnv env = ConnectorEnvFactory.listing(CONNECTION_ID);

        assertEquals(List.of("search"),
                List.copyOf(service.getTools(connection("mcp"), env, ToolAudience.MODEL).keySet()));
        // search declares nothing, so by the spec a view may call it too
        assertEquals(2, service.getTools(connection("mcp"), env, ToolAudience.VIEW).size());
        assertEquals(2, service.getTools(connection("mcp"), env, ToolAudience.ALL).size());
    }

    @Test
    @DisplayName("HTTP-листинг DYNAMIC по чужой коннекции — 404")
    void ownerScopedListing() {
        when(connectionRepository.findByIdAndUserIdNotDeleted(CONNECTION_ID, USER_ID)).thenReturn(Optional.empty());

        assertThrows(NotFoundStatusException.class, () -> service.getTools(USER_ID, "mcp", CONNECTION_ID, ToolAudience.ALL));
        verify(connectionToolRepository, never()).findActiveByConnectionId(any());
    }
}
