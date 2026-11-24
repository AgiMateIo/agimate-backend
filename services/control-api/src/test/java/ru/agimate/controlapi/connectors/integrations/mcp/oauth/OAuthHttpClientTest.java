package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.AttributionHeaders;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.service.http.PublicOnlyHttp;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OAuthHttpClient — гард адресов OAuth-цепочки")
class OAuthHttpClientTest {

    private final OAuthHttpClient client =
            new OAuthHttpClient(new PublicOnlyHttp(false), new AttributionHeaders("dev"));

    @Test
    @DisplayName("непубличный адрес блокируется до сетевого вызова")
    void blocksPrivateTargets() {
        assertTrue(assertThrows(ConnectorException.class,
                () -> client.getJson("https://169.254.169.254/.well-known/oauth-authorization-server", null))
                .getMessage().contains("not allowed"));
    }

    @Test
    @DisplayName("plain http отклоняется: по этому адресу уезжает код авторизации или токен")
    void requiresHttps() {
        assertTrue(assertThrows(ConnectorException.class,
                () -> client.postForm("http://example.com/token", Map.of("grant_type", "authorization_code")))
                .getMessage().contains("https"));
    }

    @Test
    @DisplayName("registration_endpoint с приватным адресом блокируется до запроса")
    void registrationBlocksPrivateTarget() {
        assertTrue(assertThrows(ConnectorException.class,
                () -> client.postJson("https://169.254.169.254/register",
                        JsonUtils.toJsonNodeOrNull("{}")))
                .getMessage().contains("not allowed"));
    }

    @Test
    @DisplayName("тело читается не больше потолка; превышение — отказ, а не тихое усечение")
    void bodyLimit() throws Exception {
        String body = "x".repeat(65);

        ConnectorException e = assertThrows(ConnectorException.class,
                () -> OAuthHttpClient.readBounded(
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), 64, "Metadata"));

        assertTrue(e.getMessage().contains("too large"));
    }

    @Test
    @DisplayName("тело в пределах потолка читается целиком")
    void bodyWithinLimit() throws Exception {
        assertEquals("small",
                OAuthHttpClient.readBounded(
                        new ByteArrayInputStream("small".getBytes(StandardCharsets.UTF_8)), 64, "Metadata"));
    }
}
