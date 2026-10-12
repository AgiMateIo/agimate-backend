package ru.agimate.controlapi.service.mcp.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.common.util.JsonUtils;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Правила CIMD, redirect_uri и ресурса")
class CimdAndRedirectRulesTest {

    private static final String CLIENT_ID = "https://claude.ai/oauth/mcp-client.json";

    @Nested
    @DisplayName("документ CIMD")
    class Document {

        private OAuthClient parse(String json) {
            return CimdClientResolver.parse(CLIENT_ID, URI.create(CLIENT_ID), JsonUtils.toJsonNodeOrNull(json));
        }

        @Test
        @DisplayName("верный документ: имя, хост client_id, адреса возврата")
        void valid() {
            OAuthClient client = parse("""
                    {"client_id": "https://claude.ai/oauth/mcp-client.json", "client_name": "Claude",
                     "redirect_uris": ["https://claude.ai/api/mcp/auth_callback"], "token_endpoint_auth_method": "none"}
                    """);
            assertEquals("Claude", client.name());
            assertEquals("claude.ai", client.host());
            assertEquals(1, client.redirectUris().size());
        }

        @Test
        @DisplayName("client_id внутри не равен адресу — отказ")
        void selfNameMismatch() {
            assertThrows(OAuthClientRejectedException.class, () -> parse("""
                    {"client_id": "https://claude.ai/other.json", "redirect_uris": ["https://claude.ai/cb"]}
                    """));
        }

        @Test
        @DisplayName("конфиденциальный клиент — отказ: поддерживаются только публичные")
        void confidentialClient() {
            assertThrows(OAuthClientRejectedException.class, () -> parse("""
                    {"client_id": "https://claude.ai/oauth/mcp-client.json", "redirect_uris": ["https://claude.ai/cb"],
                     "token_endpoint_auth_method": "private_key_jwt"}
                    """));
        }

        @Test
        @DisplayName("javascript: в redirect_uris отбрасывается; не осталось адресов — отказ")
        void dangerousRedirectDropped() {
            assertThrows(OAuthClientRejectedException.class, () -> parse("""
                    {"client_id": "https://claude.ai/oauth/mcp-client.json", "redirect_uris": ["javascript:alert(1)"]}
                    """));
        }

        @Test
        @DisplayName("без client_name — имя по хосту")
        void nameFallsBackToHost() {
            OAuthClient client = parse("""
                    {"client_id": "https://claude.ai/oauth/mcp-client.json", "redirect_uris": ["https://claude.ai/cb"]}
                    """);
            assertEquals("claude.ai", client.name());
        }

        @Test
        @DisplayName("срок кэша: max-age с потолком в сутки, no-store — не кэшировать, без заголовка — час")
        void cacheTtl() {
            assertEquals(Duration.ofSeconds(600), CimdClientResolver.ttlOf("public, max-age=600"));
            assertEquals(Duration.ofDays(1), CimdClientResolver.ttlOf("max-age=999999999"));
            assertEquals(Duration.ZERO, CimdClientResolver.ttlOf("no-store"));
            assertEquals(Duration.ofHours(1), CimdClientResolver.ttlOf(null));
        }
    }

    @Nested
    @DisplayName("redirect_uri")
    class Redirects {

        @Test
        @DisplayName("точное совпадение для веба: другой путь или порт — не тот адрес")
        void exactForWeb() {
            assertTrue(RedirectUris.matches("https://claude.ai/cb", "https://claude.ai/cb"));
            assertFalse(RedirectUris.matches("https://claude.ai/cb", "https://claude.ai/cb2"));
            assertFalse(RedirectUris.matches("https://claude.ai/cb", "https://claude.ai:8443/cb"));
        }

        @Test
        @DisplayName("loopback — порт не участвует в сравнении (RFC 8252)")
        void loopbackIgnoresPort() {
            assertTrue(RedirectUris.matches("http://127.0.0.1/callback", "http://127.0.0.1:33418/callback"));
            assertTrue(RedirectUris.matches("http://localhost:1/cb", "http://localhost:2/cb"));
            assertFalse(RedirectUris.matches("http://localhost/cb", "http://localhost:2/other"));
        }

        @Test
        @DisplayName("private-use схема — приложение на этом компьютере")
        void privateUseScheme() {
            assertTrue(RedirectUris.isLocalApplication(URI.create("cursor://anysphere.cursor-mcp/oauth/callback")));
            assertFalse(RedirectUris.isLocalApplication(URI.create("https://claude.ai/cb")));
        }
    }

    @Nested
    @DisplayName("ресурс")
    class Resource {

        @Test
        @DisplayName("канонизация: регистр хоста, порт по умолчанию, завершающий слэш")
        void canonical() {
            assertEquals("https://api.agimate.io/control/mcp",
                    McpOAuthEndpoints.canonical("https://API.agimate.io:443/control/mcp/"));
            assertNull(McpOAuthEndpoints.canonical("https://api.agimate.io/control/mcp#x"));
            assertNull(McpOAuthEndpoints.canonical("ftp://api.agimate.io/control/mcp"));
        }
    }
}
