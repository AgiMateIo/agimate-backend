package ru.agimate.controlapi.connectors.integrations.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.OAuthCredentials;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("McpUtils")
class McpUtilsTest {

    private static final String URL = "https://srv.example/mcp";

    @Nested
    @DisplayName("toServerConfig: Bearer")
    class Bearer {

        @Test
        @DisplayName("статический токен — из auth_token")
        void staticToken() {
            McpClient.ServerConfig config = McpUtils.toServerConfig(Map.of(
                    McpUtils.FIELD_URL, URL,
                    McpUtils.FIELD_AUTH_TOKEN, "static"));

            assertEquals("static", config.authToken());
        }

        @Test
        @DisplayName("авторизованная по OAuth коннекция — access-токен гранта, а не отвергнутый статический")
        void oauthGrant() {
            McpClient.ServerConfig config = McpUtils.toServerConfig(Map.of(
                    McpUtils.FIELD_URL, URL,
                    McpUtils.FIELD_AUTH_TOKEN, "static",
                    OAuthCredentials.ISSUER, "https://auth.example",
                    OAuthCredentials.ACCESS_TOKEN, "access-1"));

            assertEquals("access-1", config.authToken());
        }

        @Test
        @DisplayName("OAuth ещё не пройден — токена нет, запрос уходит без Authorization")
        void oauthPending() {
            McpClient.ServerConfig config = McpUtils.toServerConfig(Map.of(
                    McpUtils.FIELD_URL, URL,
                    OAuthCredentials.ISSUER, "https://auth.example"));

            assertNull(config.authToken());
        }
    }
}
