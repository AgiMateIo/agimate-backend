package ru.agimate.controlapi.connectors.integrations.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.OAuthCredentials;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

        @Test
        @DisplayName("пробелы и перевод строки вокруг токена обрезаются: SECRET-поля normalize не трогает")
        void trimsToken() {
            McpClient.ServerConfig config = McpUtils.toServerConfig(Map.of(
                    McpUtils.FIELD_URL, URL,
                    McpUtils.FIELD_AUTH_TOKEN, "  static\n"));

            assertEquals("static", config.authToken());
        }

        @Test
        @DisplayName("токен из пробелов — отсутствующий, а не пустой Authorization")
        void blankTokenIsAbsent() {
            McpClient.ServerConfig config = McpUtils.toServerConfig(Map.of(
                    McpUtils.FIELD_URL, URL,
                    McpUtils.FIELD_AUTH_TOKEN, "   "));

            assertNull(config.authToken());
        }
    }

    @Nested
    @DisplayName("toServerConfig: заголовки")
    class Headers {

        @Test
        @DisplayName("пробелы вокруг имён и значений обрезаются")
        void trimsHeaders() {
            McpClient.ServerConfig config = McpUtils.toServerConfig(Map.of(
                    McpUtils.FIELD_URL, URL,
                    McpUtils.FIELD_HEADERS, "{\" Authorization \": \"  OAuth token\\n\"}"));

            assertEquals(Map.of("Authorization", "OAuth token"), config.headers());
        }

        @Test
        @DisplayName("заголовок с null-значением отбрасывается, а не роняет запрос")
        void dropsNullValues() {
            McpClient.ServerConfig config = McpUtils.toServerConfig(Map.of(
                    McpUtils.FIELD_URL, URL,
                    McpUtils.FIELD_HEADERS, "{\"Authorization\":null,\"X-Trace\":\"on\"}"));

            assertEquals(Map.of("X-Trace", "on"), config.headers());
        }

        @Test
        @DisplayName("не-JSON — ConnectorException, а не пустой набор молча")
        void rejectsInvalidJson() {
            assertThrows(ConnectorException.class, () -> McpUtils.toServerConfig(Map.of(
                    McpUtils.FIELD_URL, URL,
                    McpUtils.FIELD_HEADERS, "not json")));
        }
    }
}
