package ru.agimate.userapi.security.oauth2;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import ru.agimate.common.util.CryptoUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("CookieOAuth2AuthorizationRequestRepository — параметры, доезжающие до колбэка")
class CookieOAuth2AuthorizationRequestRepositoryTest {

    private CookieOAuth2AuthorizationRequestRepository repository;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        repository = new CookieOAuth2AuthorizationRequestRepository(CryptoUtils.generateAES256Key(), true);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    private void save(String ref) {
        if (ref != null) {
            request.setParameter("ref", ref);
        }
        repository.saveAuthorizationRequest(OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://provider.example/authorize")
                .clientId("client-id")
                .redirectUri("https://api.example/user/login/oauth2/code/github")
                .state("state-value")
                .build(), request, response);
    }

    private Cookie refCookie() {
        return response.getCookie(CookieOAuth2AuthorizationRequestRepository.OAUTH2_REF_COOKIE_NAME);
    }

    @Nested
    @DisplayName("реферальный код")
    class Referral {

        @Test
        @DisplayName("нормальный код доезжает до колбэка в cookie на четверть часа")
        void keepsValidCode() {
            save("K7M2QX9F");

            Cookie cookie = refCookie();
            assertNotNull(cookie);
            assertEquals("K7M2QX9F", cookie.getValue());
            assertEquals(CookieOAuth2AuthorizationRequestRepository.COOKIE_EXPIRE_SECONDS,
                    cookie.getMaxAge());
        }

        @Test
        @DisplayName("значение не из алфавита в заголовок ответа не попадает")
        void dropsUnexpectedCharacters() {
            save("K7M2\r\nSet-Cookie: evil=1");

            assertNull(refCookie());
        }

        @Test
        @DisplayName("слишком длинное значение отбрасывается целиком")
        void dropsOverlongCode() {
            save("K7M2QX9FK7M2QX9FK7M2QX9F");

            assertNull(refCookie());
        }

        @Test
        @DisplayName("без параметра cookie не заводится")
        void writesNothingWithoutParameter() {
            save(null);

            assertNull(refCookie());
        }
    }

    @Nested
    @DisplayName("PKCE-challenge нативного клиента")
    class CodeChallenge {

        private static final String VALID = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

        private Cookie challengeCookie() {
            return response.getCookie(
                    CookieOAuth2AuthorizationRequestRepository.OAUTH2_CODE_CHALLENGE_COOKIE_NAME);
        }

        private void saveWithChallenge(String challenge) {
            if (challenge != null) {
                request.setParameter("code_challenge", challenge);
            }
            save(null);
        }

        @Test
        @DisplayName("S256-challenge доезжает до колбэка — без него нативный вход невозможен")
        void keepsValidChallenge() {
            saveWithChallenge(VALID);

            assertNotNull(challengeCookie());
            assertEquals(VALID, challengeCookie().getValue());
            assertEquals(CookieOAuth2AuthorizationRequestRepository.COOKIE_EXPIRE_SECONDS,
                    challengeCookie().getMaxAge());
        }

        @Test
        @DisplayName("длина не от S256 отбрасывается: другого метода мы не поддерживаем")
        void dropsWrongLength() {
            saveWithChallenge("tooshort");

            assertNull(challengeCookie());
        }

        @Test
        @DisplayName("значение не из base64url в заголовок ответа не попадает")
        void dropsUnexpectedCharacters() {
            saveWithChallenge("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSst\r\n=+");

            assertNull(challengeCookie());
        }

        @Test
        @DisplayName("веб-вход обходится без параметра — cookie не заводится")
        void writesNothingWithoutParameter() {
            saveWithChallenge(null);

            assertNull(challengeCookie());
        }
    }
}
