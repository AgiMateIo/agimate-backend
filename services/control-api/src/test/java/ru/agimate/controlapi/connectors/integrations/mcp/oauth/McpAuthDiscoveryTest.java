package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("McpAuthDiscovery")
class McpAuthDiscoveryTest {

    private static final String SERVER = "https://mcp.example.com/mcp";
    private static final String ISSUER = "https://auth.example.com";
    private static final String PROTOCOL = "2025-06-18";

    private static final String PRM = """
            {"resource": "https://mcp.example.com",
             "authorization_servers": ["https://auth.example.com"],
             "scopes_supported": ["read", "write"]}
            """;

    private static final String AS_METADATA = """
            {"issuer": "https://auth.example.com",
             "authorization_endpoint": "https://auth.example.com/authorize",
             "token_endpoint": "https://auth.example.com/token",
             "code_challenge_methods_supported": ["S256"],
             "client_id_metadata_document_supported": true}
            """;

    @Mock
    private OAuthHttpClient http;

    private McpAuthDiscovery discovery;
    private final Map<String, String> documents = new HashMap<>();

    @BeforeEach
    void setUp() {
        discovery = new McpAuthDiscovery(http);
        documents.clear();
        when(http.getJson(anyString(), any())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            String body = documents.get(url);
            return body == null ? Optional.empty() : Optional.of(JsonUtils.toJsonNodeOrNull(body));
        });
    }

    private WwwAuthenticate challenge(String header) {
        return WwwAuthenticate.parse(header).getFirst();
    }

    @Nested
    @DisplayName("поиск сервера авторизации")
    class Location {

        @Test
        @DisplayName("адрес метаданных из заголовка имеет приоритет над well-known")
        void fromHeader() {
            documents.put("https://mcp.example.com/prm", PRM);
            documents.put(ISSUER + "/.well-known/oauth-authorization-server", AS_METADATA);

            OAuthSetup setup = discovery.discover(SERVER,
                    challenge("Bearer resource_metadata=\"https://mcp.example.com/prm\""), PROTOCOL)
                    .orElseThrow();

            assertEquals(ISSUER, setup.issuer());
            assertEquals("https://auth.example.com/token", setup.tokenEndpoint());
        }

        @Test
        @DisplayName("заголовка нет — сначала well-known с путём эндпойнта, потом корневой")
        void wellKnownWithPathFirst() {
            documents.put("https://mcp.example.com/.well-known/oauth-protected-resource/mcp", PRM);
            documents.put(ISSUER + "/.well-known/oauth-authorization-server", AS_METADATA);

            assertTrue(discovery.discover(SERVER, null, PROTOCOL).isPresent());
        }

        @Test
        @DisplayName("PRM нет вовсе — легаси-путь с корня origin самого сервера")
        void legacyPath() {
            documents.put("https://mcp.example.com/.well-known/oauth-authorization-server", """
                    {"issuer": "https://mcp.example.com",
                     "authorization_endpoint": "https://mcp.example.com/authorize",
                     "token_endpoint": "https://mcp.example.com/token",
                     "code_challenge_methods_supported": ["S256"],
                     "client_id_metadata_document_supported": true}
                    """);

            OAuthSetup setup = discovery.discover(SERVER, null, PROTOCOL).orElseThrow();

            assertEquals("https://mcp.example.com", setup.issuer());
            // Канонического resource взять неоткуда — идёт введённый URL без завершающего слэша
            assertEquals(SERVER, setup.resource());
        }

        @Test
        @DisplayName("не сработал ни один из трёх путей — пусто, а не исключение")
        void nothingFound() {
            assertTrue(discovery.discover(SERVER, null, PROTOCOL).isEmpty());
        }

        @Test
        @DisplayName("OIDC-эндпойнт используется, когда RFC 8414 не ответил")
        void oidcFallback() {
            documents.put("https://mcp.example.com/.well-known/oauth-protected-resource", PRM);
            documents.put(ISSUER + "/.well-known/openid-configuration", AS_METADATA);

            assertEquals(ISSUER, discovery.discover(SERVER, null, PROTOCOL).orElseThrow().issuer());
        }
    }

    @Nested
    @DisplayName("проверки метаданных")
    class Validation {

        @BeforeEach
        void prm() {
            documents.put("https://mcp.example.com/.well-known/oauth-protected-resource", PRM);
        }

        @Test
        @DisplayName("документ с чужим issuer отвергается — и других кандидатов нет")
        void issuerMismatch() {
            documents.put(ISSUER + "/.well-known/oauth-authorization-server",
                    AS_METADATA.replace("https://auth.example.com\",", "https://evil.example\","));

            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> discovery.discover(SERVER, null, PROTOCOL));
            assertTrue(e.getMessage().contains("metadata not found"));
        }

        @Test
        @DisplayName("нет code_challenge_methods_supported — отказ")
        void noPkce() {
            documents.put(ISSUER + "/.well-known/oauth-authorization-server",
                    AS_METADATA.replace("\"code_challenge_methods_supported\": [\"S256\"],", ""));

            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> discovery.discover(SERVER, null, PROTOCOL));
            assertTrue(e.getMessage().contains("PKCE"));
        }

        @Test
        @DisplayName("в списке только plain — отказ, а не понижение метода")
        void plainOnly() {
            documents.put(ISSUER + "/.well-known/oauth-authorization-server",
                    AS_METADATA.replace("[\"S256\"]", "[\"plain\"]"));

            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> discovery.discover(SERVER, null, PROTOCOL));
            assertTrue(e.getMessage().contains("S256"));
        }

        @Test
        @DisplayName("AS не объявил CIMD — отказ: других механизмов в v1 нет")
        void noClientIdMetadataDocuments() {
            documents.put(ISSUER + "/.well-known/oauth-authorization-server",
                    AS_METADATA.replace("\"client_id_metadata_document_supported\": true", "\"x\": 1"));

            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> discovery.discover(SERVER, null, PROTOCOL));
            assertTrue(e.getMessage().contains("client ID metadata documents"));
        }

        @Test
        @DisplayName("PRM описывает другой сервер — отказ")
        void foreignResource() {
            documents.put("https://mcp.example.com/.well-known/oauth-protected-resource",
                    PRM.replace("https://mcp.example.com\",", "https://other.example\","));
            documents.put(ISSUER + "/.well-known/oauth-authorization-server", AS_METADATA);

            ConnectorException e = assertThrows(ConnectorException.class,
                    () -> discovery.discover(SERVER, null, PROTOCOL));
            assertTrue(e.getMessage().contains("different server"));
        }
    }

    @Nested
    @DisplayName("выбор scope")
    class Scope {

        @BeforeEach
        void metadata() {
            documents.put("https://mcp.example.com/.well-known/oauth-protected-resource", PRM);
            documents.put(ISSUER + "/.well-known/oauth-authorization-server", AS_METADATA);
        }

        @Test
        @DisplayName("scope из заголовка авторитетнее scopes_supported")
        void fromChallenge() {
            OAuthSetup setup = discovery.discover(SERVER,
                    challenge("Bearer scope=\"files:read\""), PROTOCOL).orElseThrow();

            assertEquals("files:read", setup.scope());
        }

        @Test
        @DisplayName("заголовок молчит — берём весь scopes_supported из PRM")
        void fromResourceMetadata() {
            assertEquals("read write", discovery.discover(SERVER, null, PROTOCOL).orElseThrow().scope());
        }

        @Test
        @DisplayName("scope нет нигде — параметр не шлём вовсе")
        void none() {
            documents.put("https://mcp.example.com/.well-known/oauth-protected-resource", """
                    {"resource": "https://mcp.example.com",
                     "authorization_servers": ["https://auth.example.com"]}
                    """);

            assertNull(discovery.discover(SERVER, null, PROTOCOL).orElseThrow().scope());
        }

        @Test
        @DisplayName("offline_access просим, только если он в метаданных AS, а не в PRM")
        void offlineAccess() {
            documents.put(ISSUER + "/.well-known/oauth-authorization-server",
                    AS_METADATA.replace("\"client_id_metadata_document_supported\": true",
                            "\"client_id_metadata_document_supported\": true,"
                                    + "\"scopes_supported\": [\"read\", \"offline_access\"]"));

            assertEquals("read write offline_access",
                    discovery.discover(SERVER, null, PROTOCOL).orElseThrow().scope());
        }
    }

    @Nested
    @DisplayName("канонический resource")
    class Resource {

        @Test
        @DisplayName("берётся из PRM, а не из введённого пользователем URL")
        void fromMetadata() {
            documents.put("https://mcp.example.com/.well-known/oauth-protected-resource", PRM);
            documents.put(ISSUER + "/.well-known/oauth-authorization-server", AS_METADATA);

            assertEquals("https://mcp.example.com",
                    discovery.discover(SERVER, null, PROTOCOL).orElseThrow().resource());
        }
    }
}
