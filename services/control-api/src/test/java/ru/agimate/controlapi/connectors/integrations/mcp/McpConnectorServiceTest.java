package ru.agimate.controlapi.connectors.integrations.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorContext;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.integrations.IntegrationValidationResult;
import ru.agimate.controlapi.database.entities.McpTool;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpConnectorService")
class McpConnectorServiceTest {

    private static final UUID IDENTITY = UUID.randomUUID();
    private static final String URL = "https://srv.example/mcp";

    @Mock
    private McpClient mcpClient;
    @Mock
    private ru.agimate.controlapi.database.repositories.McpToolRepository mcpToolRepository;

    private McpConnectorService service;

    @BeforeEach
    void setUp() {
        service = new McpConnectorService(mcpClient, mcpToolRepository);
    }

    private ConnectorContext ctx(String identity, Map<String, String> credentials) {
        return new ConnectorContext(identity, null, null, null, credentials, null);
    }

    @Nested
    @DisplayName("validateCredentials")
    class Validate {

        @Test
        @DisplayName("успех: identifier = URL, доступность подтверждена probe")
        void success() {
            when(mcpClient.probe(any())).thenReturn(new McpClient.ServerInfo("Weather", "1.0"));

            IntegrationValidationResult result = service.validateCredentials(Map.of(McpUtils.FIELD_URL, URL));

            assertTrue(result.valid());
            assertEquals(URL, result.identifier());
            assertTrue(result.displayName().contains("Weather"));
        }

        @Test
        @DisplayName("сервер недоступен/auth: failure на поле url")
        void failure() {
            when(mcpClient.probe(any())).thenThrow(new ConnectorException("401 Unauthorized"));

            IntegrationValidationResult result = service.validateCredentials(Map.of(McpUtils.FIELD_URL, URL));

            assertFalse(result.valid());
            assertEquals(McpUtils.FIELD_URL, result.errorField());
        }
    }

    @Nested
    @DisplayName("getTools(ctx)")
    class GetTools {

        @Test
        @DisplayName("читает кэш mcp_tool по identity")
        void readsCacheByIdentity() {
            when(mcpToolRepository.findByIntegrationCredentialsId(IDENTITY)).thenReturn(List.of(
                    McpTool.builder().integrationCredentialsId(IDENTITY).name("search").build(),
                    McpTool.builder().integrationCredentialsId(IDENTITY).name("fetch").build()));

            Map<String, ConnectorToolSpec> tools = service.getTools(ctx(IDENTITY.toString(), Map.of()));

            assertEquals(2, tools.size());
            assertTrue(tools.containsKey("search"));
            assertTrue(tools.containsKey("fetch"));
        }

        @Test
        @DisplayName("нет статических тулов: getTools() пуст")
        void noStaticTools() {
            assertTrue(service.getTools().isEmpty());
        }

        @Test
        @DisplayName("identity отсутствует/невалиден: пусто")
        void blankIdentity() {
            assertTrue(service.getTools(ctx(null, Map.of())).isEmpty());
            assertTrue(service.getTools(ctx("not-a-uuid", Map.of())).isEmpty());
        }
    }

    @Nested
    @DisplayName("executeTool")
    class Execute {

        @Test
        @DisplayName("проксирует в tools/call с credentials контекста")
        void proxiesToToolsCall() {
            Map<String, Object> result = Map.of("content", List.of(Map.of("type", "text", "text", "ok")));
            when(mcpClient.callTool(any(), eq("search"), any())).thenReturn(result);

            Map<String, Object> out = service.executeTool(
                    ctx(IDENTITY.toString(), Map.of(McpUtils.FIELD_URL, URL)),
                    "search", Map.of("q", "weather"));

            assertEquals(result, out);
        }
    }
}
