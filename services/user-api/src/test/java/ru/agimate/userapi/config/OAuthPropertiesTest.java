package ru.agimate.userapi.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OAuthProperties — два списка адресов возврата")
class OAuthPropertiesTest {

    private static final String WEB_URL = "https://www.agimate.io/login-check";
    private static final String LOCAL_URL = "http://www.agimate.lc:8000/login-check";
    private static final String NATIVE_URL = "agimate://auth";

    private OAuthProperties properties;

    @BeforeEach
    void setUp() {
        properties = new OAuthProperties();
        properties.setCookieDomain("agimate.io");
        properties.setFrontendRedirectUrl("https://www.agimate.io/login");
        properties.setAllowedRedirectUrls(List.of(WEB_URL));
        properties.setNativeRedirectUrls(List.of(NATIVE_URL));
    }

    @Nested
    @DisplayName("разделение веба и натива")
    class Separation {

        @Test
        @DisplayName("нативный адрес узнаётся точным совпадением")
        void recognisesNativeRedirect() {
            assertTrue(properties.isNativeRedirect(NATIVE_URL));
            assertFalse(properties.isNativeRedirect(WEB_URL));
            assertFalse(properties.isNativeRedirect(null));
        }

        @Test
        @DisplayName("префикс нативного адреса не считается своим — так заводится открытый редирект")
        void refusesPrefixMatch() {
            assertFalse(properties.isNativeRedirect("agimate://auth.evil.example"));
        }

        @Test
        @DisplayName("нативный адрес не проходит через веб-ветку и не даёт домена cookie")
        void nativeUrlFallsBackToDefaults() {
            OAuthProperties.ResolvedDomain resolved =
                    properties.resolveFromRedirectUrl(NATIVE_URL, callbackTo("api.example.com"));

            assertEquals("agimate.io", resolved.cookieDomain());
            assertEquals("https://www.agimate.io/login", resolved.frontendRedirectUrl());
        }
    }

    @Nested
    @DisplayName("фолбэк, когда redirect_to не пригодился")
    class Fallback {

        @BeforeEach
        void twoInstallations() {
            properties.setAllowedRedirectUrls(List.of(WEB_URL, LOCAL_URL));
        }

        @Test
        @DisplayName("без redirect_to адрес берётся у установки, на которую пришёл колбэк")
        void followsCallbackHost() {
            OAuthProperties.ResolvedDomain resolved =
                    properties.resolveFromRedirectUrl(null, callbackTo("api.agimate.lc"));

            assertEquals("agimate.lc", resolved.cookieDomain());
            assertEquals(LOCAL_URL, resolved.frontendRedirectUrl());
        }

        @Test
        @DisplayName("чужой redirect_to отбрасывается и установку узнать не мешает")
        void ignoresUnlistedRedirectUrl() {
            OAuthProperties.ResolvedDomain resolved = properties.resolveFromRedirectUrl(
                    "https://evil.example/login-check", callbackTo("api.agimate.lc"));

            assertEquals(LOCAL_URL, resolved.frontendRedirectUrl());
        }

        @Test
        @DisplayName("хост колбэка ни на что не похож — только тогда общий дефолт")
        void fallsBackToDefaults() {
            OAuthProperties.ResolvedDomain resolved =
                    properties.resolveFromRedirectUrl(null, callbackTo("api.example.com"));

            assertEquals("agimate.io", resolved.cookieDomain());
            assertEquals("https://www.agimate.io/login", resolved.frontendRedirectUrl());
        }
    }

    @Nested
    @DisplayName("проверка на старте")
    class Startup {

        @Test
        @DisplayName("нативный адрес в веб-списке роняет старт, а не логин")
        void refusesNativeUrlAmongWebOnes() {
            properties.setAllowedRedirectUrls(List.of(WEB_URL, NATIVE_URL));

            assertThrows(IllegalStateException.class, () -> properties.validateRedirectLists());
        }

        @Test
        @DisplayName("один адрес в обоих списках — тоже ошибка конфигурации")
        void refusesDuplicateAcrossLists() {
            properties.setNativeRedirectUrls(List.of(WEB_URL));

            assertThrows(IllegalStateException.class, () -> properties.validateRedirectLists());
        }

        @Test
        @DisplayName("правильно разложенные списки проходят")
        void acceptsWellFormedLists() {
            properties.validateRedirectLists();
        }
    }

    private static MockHttpServletRequest callbackTo(String host) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName(host);
        return request;
    }
}
