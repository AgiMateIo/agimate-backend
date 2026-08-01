package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("WwwAuthenticate")
class WwwAuthenticateTest {

    @Nested
    @DisplayName("разбор параметров")
    class Parameters {

        @Test
        @DisplayName("запятая внутри кавычек не делит параметры")
        void commaInsideQuotes() {
            List<WwwAuthenticate> challenges = WwwAuthenticate.parse(
                    "Bearer resource_metadata=\"https://mcp.example/.well-known/oauth-protected-resource\", "
                            + "scope=\"files:read, files:write\", error=\"insufficient_scope\"");

            assertEquals(1, challenges.size());
            WwwAuthenticate challenge = challenges.getFirst();
            assertEquals("files:read, files:write", challenge.parameter("scope").orElseThrow());
            assertEquals("insufficient_scope", challenge.parameter("error").orElseThrow());
            assertEquals("https://mcp.example/.well-known/oauth-protected-resource",
                    challenge.parameter("resource_metadata").orElseThrow());
        }

        @Test
        @DisplayName("значение без кавычек и регистр имени параметра")
        void unquotedAndCaseInsensitive() {
            WwwAuthenticate challenge = WwwAuthenticate.parse("Bearer Realm=api, error=invalid_token")
                    .getFirst();

            assertEquals("api", challenge.parameter("realm").orElseThrow());
            assertEquals("invalid_token", challenge.parameter("error").orElseThrow());
        }

        @Test
        @DisplayName("отсутствующий параметр и пустое значение неразличимы для вызывателя")
        void missingParameter() {
            WwwAuthenticate challenge = WwwAuthenticate.parse("Bearer scope=\"\"").getFirst();

            assertTrue(challenge.parameter("scope").isEmpty());
            assertTrue(challenge.parameter("resource_metadata").isEmpty());
        }
    }

    @Nested
    @DisplayName("несколько челленджей")
    class Challenges {

        @Test
        @DisplayName("в одном заголовке разбираются оба, Bearer находится не первым")
        void severalInOneHeader() {
            Optional<WwwAuthenticate> bearer = WwwAuthenticate.bearer(
                    List.of("Basic realm=\"legacy\", Bearer resource_metadata=\"https://as.example/prm\""));

            assertTrue(bearer.isPresent());
            assertEquals("https://as.example/prm", bearer.get().parameter("resource_metadata").orElseThrow());
        }

        @Test
        @DisplayName("несколько значений заголовка: берём первый Bearer")
        void severalHeaderValues() {
            Optional<WwwAuthenticate> bearer = WwwAuthenticate.bearer(
                    List.of("Basic realm=\"legacy\"", "Bearer scope=\"read\""));

            assertEquals("read", bearer.orElseThrow().parameter("scope").orElseThrow());
        }

        @Test
        @DisplayName("Bearer отсутствует — пусто, а не первый попавшийся челлендж")
        void noBearer() {
            assertTrue(WwwAuthenticate.bearer(List.of("Basic realm=\"legacy\"")).isEmpty());
            assertTrue(WwwAuthenticate.bearer(null).isEmpty());
        }

        @Test
        @DisplayName("пустой и мусорный заголовок не роняют разбор")
        void garbage() {
            assertTrue(WwwAuthenticate.parse("").isEmpty());
            assertTrue(WwwAuthenticate.parse(null).isEmpty());
            assertFalse(WwwAuthenticate.parse("Bearer").isEmpty());
        }
    }
}
