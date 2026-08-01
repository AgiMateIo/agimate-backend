package ru.agimate.controlapi.connectors.integrations.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.IntegrationValidationResult;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.McpAuthDiscovery;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.McpOAuthService;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.McpUnauthorizedException;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.OAuthCredentials;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.OAuthSetup;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.WwwAuthenticate;
import ru.agimate.controlapi.database.entities.ConnectionTool;
import ru.agimate.controlapi.database.repositories.ConnectionToolRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpConnectorService")
class McpConnectorServiceTest {

    private static final UUID IDENTITY = UUID.randomUUID();
    private static final String URL = "https://srv.example/mcp";

    @Mock
    private McpClient mcpClient;
    @Mock
    private ConnectionToolRepository connectionToolRepository;
    @Mock
    private McpAuthDiscovery authDiscovery;
    @Mock
    private McpOAuthService oauthService;

    private McpConnectorService service;

    @BeforeEach
    void setUp() {
        service = new McpConnectorService(mcpClient, connectionToolRepository, authDiscovery, oauthService);
    }

    private ConnectorEnv ctx(String connectionId, Map<String, String> credentials) {
        return new ConnectorEnv(connectionId, null, null, null, null, null, credentials, null);
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

        @Test
        @DisplayName("401 и найденный AS: третий исход, добытое едет в derivedCredentials")
        void authorizationRequired() {
            when(mcpClient.probe(any())).thenThrow(unauthorized(null));
            when(authDiscovery.discover(eq(URL), any(), any())).thenReturn(Optional.of(new OAuthSetup(
                    "https://auth.example.com",
                    "https://auth.example.com/authorize",
                    "https://auth.example.com/token",
                    "https://srv.example/mcp",
                    "read")));

            IntegrationValidationResult result = service.validateCredentials(Map.of(McpUtils.FIELD_URL, URL));

            assertTrue(result.valid());
            assertTrue(result.authorizationRequired());
            assertEquals("https://auth.example.com",
                    result.derivedCredentials().get(OAuthCredentials.ISSUER));
            assertEquals("read", result.derivedCredentials().get(OAuthCredentials.SCOPE_REQUESTED));
        }

        @Test
        @DisplayName("401, но сервер авторизации не найден: отказ с предложением статического токена")
        void authorizationServerNotFound() {
            when(mcpClient.probe(any())).thenThrow(unauthorized(null));
            when(authDiscovery.discover(eq(URL), any(), any())).thenReturn(Optional.empty());

            IntegrationValidationResult result = service.validateCredentials(Map.of(McpUtils.FIELD_URL, URL));

            assertFalse(result.valid());
            assertTrue(result.errorMessage().contains("static token"));
        }

        @Test
        @DisplayName("отказ discovery (нет CIMD, нет S256) — обычная ошибка валидации")
        void discoveryRefusal() {
            when(mcpClient.probe(any())).thenThrow(unauthorized(null));
            when(authDiscovery.discover(eq(URL), any(), any()))
                    .thenThrow(new ConnectorException("does not support the S256 PKCE method"));

            IntegrationValidationResult result = service.validateCredentials(Map.of(McpUtils.FIELD_URL, URL));

            assertFalse(result.valid());
            assertTrue(result.errorMessage().contains("S256"));
        }
    }

    @Nested
    @DisplayName("getTools(ctx)")
    class GetTools {

        @Test
        @DisplayName("читает кэш connection_tools по connectionId")
        void readsCacheByIdentity() {
            when(connectionToolRepository.findActiveByConnectionId(IDENTITY)).thenReturn(List.of(
                    ConnectionTool.builder().connectionId(IDENTITY).name("search").build(),
                    ConnectionTool.builder().connectionId(IDENTITY).name("fetch").build()));

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
        @DisplayName("connectionId отсутствует/невалиден: пусто")
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

        @Test
        @DisplayName("401 в вызове: помечаем AUTH_EXPIRED и отдаём агенту ссылку, а не обновляем токен")
        void unauthorizedMarksExpired() {
            when(mcpClient.callTool(any(), eq("search"), any())).thenThrow(unauthorized(null));

            ConnectorException e = assertThrows(ConnectorException.class, () -> service.executeTool(
                    ctx(IDENTITY.toString(), Map.of(McpUtils.FIELD_URL, URL)), "search", Map.of()));

            verify(oauthService).markExpired(IDENTITY);
            assertTrue(e.getMessage().contains("Re-connect"));
        }

        @Test
        @DisplayName("403 insufficient_scope: грант жив, помечать протухшим нечего")
        void insufficientScopeKeepsGrant() {
            when(mcpClient.callTool(any(), eq("search"), any()))
                    .thenThrow(unauthorized("Bearer error=\"insufficient_scope\", scope=\"files:write\""));

            ConnectorException e = assertThrows(ConnectorException.class, () -> service.executeTool(
                    ctx(IDENTITY.toString(), Map.of(McpUtils.FIELD_URL, URL)), "search", Map.of()));

            verify(oauthService, never()).markExpired(any());
            assertTrue(e.getMessage().contains("files:write"));
        }
    }

    @Nested
    @DisplayName("джоба обновления токена")
    class Jobs {

        @Test
        @DisplayName("объявлена одна периодическая джоба на инстанс")
        void declaresRefreshJob() {
            assertTrue(service.getJobs().containsKey(McpConnectorService.JOB_OAUTH_REFRESH));
        }

        @Test
        @DisplayName("исполнение делегируется в OAuth-сервис по connectionId")
        void executesRefresh() {
            when(oauthService.refreshIfNeeded(IDENTITY)).thenReturn(true);

            Map<String, Object> result = service.executeJob(
                    ctx(IDENTITY.toString(), Map.of()), McpConnectorService.JOB_OAUTH_REFRESH, Map.of());

            assertEquals(true, result.get("refreshed"));
        }

        @Test
        @DisplayName("чужое имя джобы — ошибка, а не молчаливый no-op")
        void unknownJob() {
            assertThrows(ConnectorException.class, () -> service.executeJob(
                    ctx(IDENTITY.toString(), Map.of()), "whatever", Map.of()));
        }
    }

    /** A 401 (or a 403 with a challenge) the way {@link McpClient} surfaces it. */
    private static McpUnauthorizedException unauthorized(String header) {
        WwwAuthenticate challenge = header == null ? null : WwwAuthenticate.parse(header).getFirst();
        boolean insufficientScope = challenge != null
                && challenge.parameter("error").filter("insufficient_scope"::equals).isPresent();
        return new McpUnauthorizedException("MCP server requires authorization", challenge, insufficientScope);
    }
}
