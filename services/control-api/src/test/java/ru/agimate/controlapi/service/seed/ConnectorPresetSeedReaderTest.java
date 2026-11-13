package ru.agimate.controlapi.service.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import ru.agimate.controlapi.database.enums.ConnectorPresetAuth;
import ru.agimate.controlapi.database.enums.ConnectorPresetAvailability;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.database.model.PresetFieldOverride;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConnectorPresetSeedReader — разбор JSON и вывод полей каталога")
class ConnectorPresetSeedReaderTest {

    private final ConnectorPresetSeedReader reader = new ConnectorPresetSeedReader();

    @Nested
    @DisplayName("DCR-сервер со статическим токеном")
    class DcrWithStaticToken {

        @Test
        @DisplayName("OAUTH отфильтрован, Bearer становится auth_token, карточка доступна")
        void filtersOauthAndKeepsToken() {
            ConnectorPresetSeed seed = read("bitrix24", """
                    {
                      "name": "Bitrix24",
                      "description": "CRM",
                      "category": "crm",
                      "jurisdiction": { "country": "RU" },
                      "url": "https://mcp.bitrix24.tech/mcp/",
                      "declared": {
                        "transport": "streamable-http",
                        "authMethods": ["oauth2.1", "token"],
                        "headers": [
                          { "name": "Authorization", "isRequired": true, "isSecret": true,
                            "valuePrefix": "Bearer ", "label": "API token", "labelRu": "API-токен" }
                        ]
                      },
                      "probe": {
                        "status": "auth_required",
                        "transport": "streamable-http",
                        "transportConfirmed": false,
                        "authType": "oauth2.1_dcr",
                        "authDetails": { "clientIdMetadataDocumentSupported": false }
                      }
                    }
                    """);

            assertEquals(List.of(ConnectorPresetAuth.TOKEN), seed.authMethods());
            assertTrue(seed.headerFields() == null || seed.headerFields().isEmpty());
            assertEquals("API token", seed.fields().get("auth_token").label());
            assertEquals(Boolean.TRUE, seed.fields().get("auth_token").required());
            assertEquals(ConnectorPresetAvailability.AVAILABLE, seed.availability());
            assertEquals("https://mcp.bitrix24.tech/mcp/", seed.params().get("url"));
            assertEquals("https://mcp.bitrix24.tech/mcp/", seed.identity());
            assertEquals(ContentCategory.WORK, seed.category());
        }
    }

    @Nested
    @DisplayName("OAuth")
    class Oauth {

        @Test
        @DisplayName("CIMD оставляет OAUTH и не показывает auth_token")
        void cimdKeepsOauth() {
            ConnectorPresetSeed seed = read("cimd", """
                    {
                      "name": "CIMD",
                      "category": "dev-tools",
                      "url": "https://example.com/mcp",
                      "declared": { "transport": "streamable-http", "authMethods": ["oauth2.1"] },
                      "probe": {
                        "status": "auth_required", "transport": "streamable-http",
                        "transportConfirmed": false, "authType": "oauth2.1_dcr",
                        "authDetails": { "clientIdMetadataDocumentSupported": true }
                      }
                    }
                    """);

            assertEquals(List.of(ConnectorPresetAuth.OAUTH), seed.authMethods());
            assertNull(seed.fields());
            assertEquals(ConnectorPresetAvailability.AVAILABLE, seed.availability());
        }

        @Test
        @DisplayName("DCR-only без токена оставляет пустой список и AUTH_UNSUPPORTED")
        void dcrOnlyIsUnsupported() {
            ConnectorPresetSeed seed = read("dcr-only", """
                    {
                      "name": "DCR only",
                      "category": "dev-tools",
                      "url": "https://example.com/mcp",
                      "declared": { "transport": "streamable-http", "authMethods": ["oauth2.1"] },
                      "probe": {
                        "status": "auth_required", "transport": "streamable-http",
                        "transportConfirmed": false, "authType": "oauth2.1_dcr",
                        "authDetails": { "clientIdMetadataDocumentSupported": false }
                      }
                    }
                    """);

            assertTrue(seed.authMethods().isEmpty());
            assertEquals(ConnectorPresetAvailability.AUTH_UNSUPPORTED, seed.availability());
        }
    }

    @Nested
    @DisplayName("кастомный заголовок")
    class CustomHeader {

        @Test
        @DisplayName("OAuth-префикс остаётся заголовком, auth_token не появляется")
        void oauthPrefixStaysHeader() {
            ConnectorPresetSeed seed = read("yandex-tracker", """
                    {
                      "name": "Yandex Tracker",
                      "category": "productivity",
                      "jurisdiction": { "country": "RU" },
                      "url": "https://mcp.tracker.yandex.net/mcp",
                      "declared": {
                        "headers": [
                          { "name": "Authorization", "isRequired": true, "isSecret": true,
                            "valuePrefix": "OAuth ", "label": "OAuth token", "labelRu": "OAuth-токен" },
                          { "name": "x-org-id", "isRequired": false, "isSecret": false,
                            "valuePrefix": "", "label": "Organization ID", "labelRu": "Идентификатор организации" },
                          { "name": "x-cloud-org-id", "isRequired": false, "isSecret": false,
                            "valuePrefix": "", "label": "Cloud organization ID", "labelRu": "Идентификатор облачной организации" }
                        ],
                        "headerGroups": [
                          { "id": "org", "anyOf": ["x-org-id", "x-cloud-org-id"], "required": true }
                        ]
                      },
                      "probe": { "status": "up", "transport": "streamable-http", "transportConfirmed": true,
                                 "authType": "none", "handshake": { "supported": true } }
                    }
                    """);

            assertEquals(List.of(ConnectorPresetAuth.TOKEN), seed.authMethods());
            assertEquals(3, seed.headerFields().size());
            assertNull(seed.fields());
            assertEquals(1, seed.headerGroups().size());
            assertEquals(List.of("x-org-id", "x-cloud-org-id"), seed.headerGroups().get(0).oneOf());
            assertEquals(ConnectorPresetAvailability.AVAILABLE, seed.availability());
        }

        @Test
        @DisplayName("пустой valuePrefix — сырое значение заголовка, а не Bearer")
        void emptyPrefixIsRawHeader() {
            ConnectorPresetSeed seed = read("krasoft", """
                    {
                      "name": "Krasoft",
                      "category": "dev-tools",
                      "url": "https://example.com/mcp",
                      "declared": {
                        "authMethods": ["token"],
                        "headers": [
                          { "name": "Authorization", "isRequired": true, "isSecret": true,
                            "valuePrefix": "", "keyPrefix": "krsp_", "label": "API key" }
                        ]
                      },
                      "probe": { "status": "auth_required", "transport": "streamable-http",
                                 "transportConfirmed": false, "authType": "none" }
                    }
                    """);

            assertEquals(List.of(ConnectorPresetAuth.TOKEN), seed.authMethods());
            assertEquals(1, seed.headerFields().size());
            assertEquals("Authorization", seed.headerFields().get(0).name());
            assertEquals("krsp_", seed.headerFields().get(0).keyPrefix());
            assertNull(seed.fields());
        }
    }

    @Nested
    @DisplayName("оговорки")
    class Overrides {

        @Test
        @DisplayName("override authMethods добавляет TOKEN, fields задаёт подпись")
        void overrideAddsTokenAndLabel() {
            PresetOverrides overrides = new PresetOverrides(Map.of(), Map.of("square",
                    new PresetOverride(List.of(ConnectorPresetAuth.OAUTH, ConnectorPresetAuth.TOKEN),
                            Map.of("auth_token", new PresetFieldOverride("Square token", "Токен Square", true)),
                            null)));

            ConnectorPresetSeed seed = reader.read(resource("""
                    {
                      "name": "Square",
                      "category": "payments",
                      "url": "https://example.com/mcp",
                      "declared": { "transport": "streamable-http" },
                      "probe": { "status": "auth_required", "transport": "streamable-http",
                                 "transportConfirmed": false, "authType": "oauth2.1_dcr",
                                 "authDetails": { "clientIdMetadataDocumentSupported": false } }
                    }
                    """), "mcp", "square", overrides);

            assertEquals(List.of(ConnectorPresetAuth.TOKEN), seed.authMethods());
            assertEquals("Square token", seed.fields().get("auth_token").label());
            assertEquals(ConnectorPresetAvailability.AVAILABLE, seed.availability());
        }
    }

    @Nested
    @DisplayName("транспорт и протокол")
    class TransportAndProtocol {

        @Test
        @DisplayName("исходный /sse у auth_required не подтверждает транспорт")
        void legacySseBlocks() {
            ConnectorPresetSeed seed = read("legacy", """
                    {
                      "name": "Legacy",
                      "category": "dev-tools",
                      "url": "https://example.com/sse",
                      "declared": { "authMethods": ["token"],
                                    "headers": [{ "name": "Authorization", "isRequired": true, "isSecret": true,
                                                  "valuePrefix": "Bearer " }] },
                      "probe": { "status": "auth_required", "transport": "streamable-http",
                                 "transportConfirmed": false,
                                 "endpointUrl": "https://example.com/mcp" }
                    }
                    """);

            assertEquals(ConnectorPresetAvailability.TRANSPORT_UNSUPPORTED, seed.availability());
        }

        @Test
        @DisplayName("up с handshake false — PROTOCOL_UNSUPPORTED")
        void handshakeFalseBlocksUp() {
            ConnectorPresetSeed seed = read("up", """
                    {
                      "name": "Up",
                      "category": "dev-tools",
                      "url": "https://example.com/mcp",
                      "declared": { "authMethods": ["none"] },
                      "probe": { "status": "up", "transport": "streamable-http",
                                 "transportConfirmed": true, "authType": "none",
                                 "handshake": { "supported": false } }
                    }
                    """);

            assertEquals(List.of(ConnectorPresetAuth.NONE), seed.authMethods());
            assertEquals(ConnectorPresetAvailability.PROTOCOL_UNSUPPORTED, seed.availability());
        }
    }

    @Nested
    @DisplayName("пустые строки")
    class BlankStrings {

        @Test
        @DisplayName("пустые переводы и подписи становятся null, подпись падает на имя")
        void blankTranslationsAndLabelsBecomeNull() {
            ConnectorPresetSeed seed = read("blank", """
                    {
                      "name": "Blank",
                      "nameRu": "   ",
                      "descriptionRu": "",
                      "category": "dev-tools",
                      "url": "https://example.com/mcp",
                      "declared": {
                        "authMethods": ["token"],
                        "headers": [{ "name": "X-API-Token", "isRequired": true, "isSecret": true,
                                      "valuePrefix": "", "label": "  ", "labelRu": "" }]
                      },
                      "probe": { "status": "auth_required", "transport": "streamable-http",
                                 "transportConfirmed": false, "authType": "none" }
                    }
                    """);

            assertNull(seed.nameRu());
            assertNull(seed.descriptionRu());
            assertEquals("X-API-Token", seed.headerFields().get(0).label());
            assertNull(seed.headerFields().get(0).labelRu());
        }

        @Test
        @DisplayName("пустое имя — ошибка сида")
        void blankNameFails() {
            assertThrows(IllegalStateException.class, () -> read("blank-name", """
                    {
                      "name": "   ",
                      "category": "dev-tools",
                      "url": "https://example.com/mcp",
                      "declared": { "authMethods": ["none"] },
                      "probe": { "status": "up", "transport": "streamable-http",
                                 "transportConfirmed": true, "authType": "none",
                                 "handshake": { "supported": true } }
                    }
                    """));
        }
    }

    private ConnectorPresetSeed read(String code, String json) {
        return reader.read(resource(json), "mcp", code, PresetOverrides.empty());
    }

    private static Resource resource(String json) {
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
    }
}
