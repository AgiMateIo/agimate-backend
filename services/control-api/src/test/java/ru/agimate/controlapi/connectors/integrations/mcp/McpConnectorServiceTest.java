package ru.agimate.controlapi.connectors.integrations.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.dto.CredentialField;
import ru.agimate.controlapi.connectors.core.dto.IntegrationValidationResult;
import ru.agimate.controlapi.connectors.core.dto.ViewCallResult;
import ru.agimate.controlapi.connectors.core.dto.ViewPage;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.McpAuthDiscovery;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.McpOAuthService;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.McpUnauthorizedException;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.OAuthCredentials;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.OAuthSetup;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.WwwAuthenticate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private McpAuthDiscovery authDiscovery;
    @Mock
    private McpOAuthService oauthService;

    private McpConnectorService service;

    @BeforeEach
    void setUp() {
        service = new McpConnectorService(mcpClient, authDiscovery, oauthService);
    }

    private ConnectorEnv ctx(String connectionId, Map<String, String> credentials) {
        return new ConnectorEnv(connectionId, null, null, null, null, null, credentials, null);
    }

    @Nested
    @DisplayName("getCredentialFields")
    class CredentialFields {

        @Test
        @DisplayName("маскируется только токен, URL и заголовки — открытые поля")
        void types() {
            Map<String, CredentialField> fields = service.getCredentialFields();

            assertEquals(CredentialField.Type.URL, fields.get(McpUtils.FIELD_URL).type());
            assertEquals(CredentialField.Type.SECRET, fields.get(McpUtils.FIELD_AUTH_TOKEN).type());
            assertEquals(CredentialField.Type.JSON, fields.get(McpUtils.FIELD_HEADERS).type());
        }

        @Test
        @DisplayName("обязателен только URL, и подписи не несут слова optional")
        void requiredness() {
            Map<String, CredentialField> fields = service.getCredentialFields();

            assertTrue(fields.get(McpUtils.FIELD_URL).required());
            assertFalse(fields.get(McpUtils.FIELD_AUTH_TOKEN).required());
            assertFalse(fields.get(McpUtils.FIELD_HEADERS).required());
            fields.values().forEach(field -> assertFalse(field.label().contains("optional")));
        }

        @Test
        @DisplayName("порядок полей сохраняется: URL первым")
        void order() {
            assertEquals(McpUtils.FIELD_URL, service.getCredentialFields().keySet().iterator().next());
        }
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
    @DisplayName("getTools")
    class GetTools {

        @Test
        @DisplayName("нет статических тулов: getTools() пуст")
        void noStaticTools() {
            assertTrue(service.getTools().isEmpty());
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
    @DisplayName("вью")
    class Views {

        private static final String VIEW = "ui://server-everything/weather-dashboard";

        @Test
        @DisplayName("страница с prefersBorder из _meta.ui; объявленные сервером домены не отдаются")
        void readsPage() {
            when(mcpClient.readResource(any(), eq(VIEW))).thenReturn(new McpClient.Resource(VIEW,
                    "text/html;profile=mcp-app", "<html></html>", JsonUtils.toJsonNodeOrNull(
                    "{\"ui\":{\"csp\":{\"connectDomains\":[\"https://evil.example\"]},\"prefersBorder\":true}}")));

            ViewPage page = service.readView(ctx(IDENTITY.toString(), Map.of(McpUtils.FIELD_URL, URL)), VIEW);

            assertEquals("<html></html>", page.html());
            assertTrue(page.prefersBorder());
            assertNull(page.permissions());
            assertFalse(JsonUtils.writeValueAsString(page).contains("evil.example"));
        }

        @Test
        @DisplayName("не страница MCP App → ConnectorException")
        void rejectsOtherMimeType() {
            when(mcpClient.readResource(any(), eq(VIEW)))
                    .thenReturn(new McpClient.Resource(VIEW, "text/html", "<html></html>", null));

            assertThrows(ConnectorException.class,
                    () -> service.readView(ctx(IDENTITY.toString(), Map.of(McpUtils.FIELD_URL, URL)), VIEW));
        }

        @Test
        @DisplayName("MIME вью: пробелы, регистр и кавычки не важны, похожий профиль не проходит")
        void viewMimeType() {
            assertTrue(McpConnectorService.isViewMimeType("text/html;profile=mcp-app"));
            assertTrue(McpConnectorService.isViewMimeType("Text/HTML; charset=utf-8; Profile=\"MCP-App\""));
            assertFalse(McpConnectorService.isViewMimeType("text/html;profile=mcp-app-x"));
            assertFalse(McpConnectorService.isViewMimeType("text/plain;profile=mcp-app"));
            assertFalse(McpConnectorService.isViewMimeType(null));
        }

        @Test
        @DisplayName("результат вызова: CallToolResult сервера доходит до вью со structuredContent")
        void callToolResultPassesThrough() {
            ViewCallResult result = service.toViewResult("""
                    {"content":[{"type":"text","text":"12°C"}],"structuredContent":{"temp":12},"isError":false}""");

            assertEquals(List.of(Map.of("type", "text", "text", "12°C")), result.content());
            assertEquals(Map.of("temp", 12), result.structuredContent());
            assertFalse(result.isError());
        }

        @Test
        @DisplayName("вывод не в форме CallToolResult — текстом")
        void otherOutputIsText() {
            ViewCallResult result = service.toViewResult("{\"content\":\"not a list\"}");

            assertEquals(List.of(Map.of("type", "text", "text", "{\"content\":\"not a list\"}")), result.content());
            assertNull(result.structuredContent());
        }
    }

    @Nested
    @DisplayName("джоба обновления токена")
    class Jobs {

        @Test
        @DisplayName("объявлена одна периодическая джоба")
        void declaresRefreshJob() {
            assertTrue(service.getJobs().containsKey(McpConnectorService.JOB_OAUTH_REFRESH));
        }

        @Test
        @DisplayName("инстанс со сроком гранта получает строку")
        void instanceWithExpiryGetsJob() {
            when(oauthService.tracksExpiry(IDENTITY)).thenReturn(true);

            assertTrue(service.getJobs(ctx(IDENTITY.toString(), Map.of()))
                    .containsKey(McpConnectorService.JOB_OAUTH_REFRESH));
        }

        @Test
        @DisplayName("инстанс без срока (статический токен) строки не получает")
        void instanceWithoutExpiryGetsNothing() {
            when(oauthService.tracksExpiry(IDENTITY)).thenReturn(false);

            assertTrue(service.getJobs(ctx(IDENTITY.toString(), Map.of())).isEmpty());
        }

        @Test
        @DisplayName("без connectionId джоб нет")
        void noInstanceNoJobs() {
            assertTrue(service.getJobs(ctx(null, Map.of())).isEmpty());
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
