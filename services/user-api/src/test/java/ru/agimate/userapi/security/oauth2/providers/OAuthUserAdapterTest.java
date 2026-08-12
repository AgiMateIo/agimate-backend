package ru.agimate.userapi.security.oauth2.providers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Адаптеры OAuth-провайдеров — извлечение профиля")
class OAuthUserAdapterTest {

    private static final String ACCESS_TOKEN = "token";

    private static OAuth2User user(Map<String, Object> attributes, String nameAttribute) {
        return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, nameAttribute);
    }

    private static OAuth2UserRequest userRequest(String registrationId) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(registrationId)
                .clientId("client-id")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/" + registrationId)
                .authorizationUri("https://example.test/authorize")
                .tokenUri("https://example.test/token")
                .userInfoUri("https://example.test/userinfo")
                .userNameAttributeName("id")
                .build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, ACCESS_TOKEN,
                Instant.now(), Instant.now().plusSeconds(60));
        return new OAuth2UserRequest(registration, accessToken);
    }

    @Nested
    @DisplayName("Google")
    class Google {

        private final GoogleUserAdapter adapter = new GoogleUserAdapter();

        @Test
        @DisplayName("профиль из claims, подтверждённость почты — из email_verified")
        void extractsProfile() {
            OAuthUserInfo info = adapter.extract(user(Map.of(
                    "sub", "10001",
                    "email", "ivan@example.com",
                    "email_verified", true,
                    "given_name", "Иван",
                    "family_name", "Петров",
                    "name", "Иван Петров"), "sub"));

            assertEquals("10001", info.providerUserId());
            assertEquals("ivan@example.com", info.email());
            assertTrue(info.emailVerified());
            assertEquals("Иван", info.firstName());
            assertEquals("Петров", info.lastName());
            assertEquals("Иван Петров", info.displayName());
        }

        @Test
        @DisplayName("без email_verified почта считается неподтверждённой")
        void treatsMissingFlagAsUnverified() {
            OAuthUserInfo info = adapter.extract(user(Map.of(
                    "sub", "10001",
                    "email", "ivan@example.com"), "sub"));

            assertFalse(info.emailVerified());
        }
    }

    @Nested
    @DisplayName("Yandex")
    class Yandex {

        private final YandexUserAdapter adapter = new YandexUserAdapter();

        @Test
        @DisplayName("id и default_email как почта аккаунта")
        void extractsProfile() {
            OAuthUserInfo info = adapter.extract(user(Map.of(
                    "id", "20002",
                    "default_email", "ivan@yandex.ru",
                    "first_name", "Иван",
                    "last_name", "Петров",
                    "display_name", "ivan"), "id"));

            assertEquals("20002", info.providerUserId());
            assertEquals("ivan@yandex.ru", info.email());
            assertTrue(info.emailVerified());
            assertEquals("ivan", info.displayName());
        }
    }

    @Nested
    @DisplayName("GitHub")
    class Github {

        private final GithubEmailClient emailClient = mock(GithubEmailClient.class);
        private final GithubUserAdapter adapter = new GithubUserAdapter(emailClient);

        @Test
        @DisplayName("публичная почта профиля заменяется на подтверждённую основную")
        void replacesPublicEmailWithVerifiedOne() {
            when(emailClient.primaryVerifiedEmail(ACCESS_TOKEN)).thenReturn(Optional.of("ivan@example.com"));

            Map<String, Object> normalized = adapter.normalize(userRequest("github"),
                    Map.of("id", 30003, "login", "ivan", "email", "public@example.com"));

            assertEquals("ivan@example.com", normalized.get("email"));
        }

        @Test
        @DisplayName("нет подтверждённой почты — атрибута нет вовсе, публичный не подставляется")
        void dropsUnverifiedPublicEmail() {
            when(emailClient.primaryVerifiedEmail(ACCESS_TOKEN)).thenReturn(Optional.empty());

            Map<String, Object> normalized = adapter.normalize(userRequest("github"),
                    Map.of("id", 30003, "login", "ivan", "email", "public@example.com"));

            assertFalse(normalized.containsKey("email"));
            assertNull(adapter.extract(user(normalized, "id")).email());
        }

        @Test
        @DisplayName("GitHub недоступен — ошибка аутентификации, а не молчаливый вход без почты")
        void failsWhenEmailsCannotBeRead() {
            when(emailClient.primaryVerifiedEmail(anyString())).thenThrow(new RestClientException("boom"));

            assertThrows(OAuth2AuthenticationException.class,
                    () -> adapter.normalize(userRequest("github"), Map.of("id", 30003, "login", "ivan")));
        }

        @Test
        @DisplayName("числовой id приводится к строке, имя берётся из name, иначе из login")
        void extractsProfile() {
            OAuthUserInfo info = adapter.extract(user(Map.of(
                    "id", 30003,
                    "login", "ivan",
                    "email", "ivan@example.com"), "id"));

            assertEquals("30003", info.providerUserId());
            assertTrue(info.emailVerified());
            assertEquals("ivan", info.displayName());
            assertNull(info.firstName());
        }
    }

    @Nested
    @DisplayName("VK ID")
    class Vk {

        private final VkUserAdapter adapter = new VkUserAdapter();

        @Test
        @DisplayName("вложенный user поднимается наверх — иначе Spring не найдёт user_id")
        void flattensNestedUser() {
            Map<String, Object> normalized = adapter.normalize(userRequest("vk"),
                    Map.of("user", Map.of("user_id", "40004", "first_name", "Иван", "last_name", "Петров")));

            assertEquals("40004", normalized.get("user_id"));
            assertEquals("Иван", normalized.get("first_name"));
        }

        @Test
        @DisplayName("ответ без user — ошибка аутентификации")
        void rejectsResponseWithoutUser() {
            assertThrows(OAuth2AuthenticationException.class,
                    () -> adapter.normalize(userRequest("vk"), Map.of("error", "invalid_token")));
        }

        @Test
        @DisplayName("имя собирается из первого и фамилии, почта необязательна")
        void extractsProfile() {
            OAuthUserInfo info = adapter.extract(user(Map.of(
                    "user_id", "40004",
                    "first_name", "Иван",
                    "last_name", "Петров"), "user_id"));

            assertEquals("40004", info.providerUserId());
            assertNull(info.email());
            assertEquals("Иван Петров", info.displayName());
        }
    }
}
