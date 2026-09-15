package ru.agimate.common.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.Proxy;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EgressProxy — системный прокси по списку хостов")
class EgressProxyTest {

    private static final String URL = "http://agimate:s3cr%40t@203.0.113.10:3128";

    @Nested
    @DisplayName("совпадение хоста")
    class Matching {

        private final EgressProxy proxy = EgressProxy.of(URL, List.of("openrouter.ai", "*.openai.com", " API.Telegram.org "));

        @Test
        @DisplayName("точное имя совпадает без учёта регистра, соседнее — нет")
        void exactName() {
            assertTrue(proxy.covers("openrouter.ai"));
            assertTrue(proxy.covers("OpenRouter.AI"));
            assertTrue(proxy.covers("api.telegram.org"));
            assertFalse(proxy.covers("api.openrouter.ai"));
            assertFalse(proxy.covers("evilopenrouter.ai"));
        }

        @Test
        @DisplayName("*.domain — поддомены любой глубины, но не сам домен")
        void wildcard() {
            assertTrue(proxy.covers("api.openai.com"));
            assertTrue(proxy.covers("a.b.openai.com"));
            assertFalse(proxy.covers("openai.com"));
            assertFalse(proxy.covers("notopenai.com"));
        }

        @Test
        @DisplayName("select: прокси для хоста из списка, напрямую для остальных")
        void selects() {
            assertEquals(Proxy.Type.HTTP, proxy.select(URI.create("https://openrouter.ai/api/v1")).getFirst().type());
            assertSame(Proxy.NO_PROXY, proxy.select(URI.create("https://api.polza.ai/v1")).getFirst());
        }

        @Test
        @DisplayName("адрес и логин разобраны, пароль раскодирован и не попадает в toString")
        void parsesUrl() {
            assertEquals("203.0.113.10", proxy.host());
            assertEquals(3128, proxy.port());
            assertEquals("agimate", proxy.username());
            assertEquals("s3cr@t", proxy.password());
            assertFalse(proxy.toString().contains("s3cr"));
        }

        @Test
        @DisplayName("без url прокси выключен и ничего не покрывает — даже если хосты перечислены")
        void none() {
            for (EgressProxy none : List.of(EgressProxy.of("", List.of()), EgressProxy.of(null, List.of("openrouter.ai")))) {
                assertSame(EgressProxy.NONE, none);
                assertFalse(none.enabled());
                assertFalse(none.covers("openrouter.ai"));
                assertSame(Proxy.NO_PROXY, none.select(URI.create("https://openrouter.ai")).getFirst());
            }
        }
    }

    @Nested
    @DisplayName("остановка старта")
    class Refusals {

        @Test
        @DisplayName("url без hosts: прокси задан, но через него не пошло бы ничего")
        void urlWithoutHosts() {
            assertThrows(IllegalStateException.class, () -> EgressProxy.of(URL, List.of()));
            assertThrows(IllegalStateException.class, () -> EgressProxy.of(URL, List.of(" ", "")));
        }

        @Test
        @DisplayName("не http, без порта, без логина или пароля")
        void badUrl() {
            List<String> hosts = List.of("openrouter.ai");
            assertThrows(IllegalStateException.class, () -> EgressProxy.of("https://u:p@203.0.113.10:3128", hosts));
            assertThrows(IllegalStateException.class, () -> EgressProxy.of("socks5://u:p@203.0.113.10:1080", hosts));
            assertThrows(IllegalStateException.class, () -> EgressProxy.of("http://u:p@203.0.113.10", hosts));
            assertThrows(IllegalStateException.class, () -> EgressProxy.of("http://203.0.113.10:3128", hosts));
            assertThrows(IllegalStateException.class, () -> EgressProxy.of("http://u@203.0.113.10:3128", hosts));
        }

        @Test
        @DisplayName("незакодированный @ или # в пароле — подсказка про кодирование, а не «нет хоста»")
        void unencodedPassword() {
            List<String> hosts = List.of("openrouter.ai");
            for (String url : List.of("http://u:p@ss@203.0.113.10:3128", "http://u:p#ss@203.0.113.10:3128")) {
                IllegalStateException e = assertThrows(IllegalStateException.class, () -> EgressProxy.of(url, hosts));
                assertTrue(e.getMessage().contains("%40"), e.getMessage());
            }
            assertEquals("p@ss", EgressProxy.of("http://u:p%40ss@203.0.113.10:3128", hosts).password());
        }

        @Test
        @DisplayName("хост URL-ом или с портом: иначе он принялся бы и никогда не совпал")
        void notABareHost() {
            for (String host : List.of("https://openrouter.ai", "api.openai.com:443", "openrouter.ai/api", "user@host.com")) {
                assertThrows(IllegalStateException.class, () -> EgressProxy.of(URL, List.of(host)), host);
            }
        }

        @Test
        @DisplayName("* и шаблон над публичным суффиксом: там имя заводит кто угодно")
        void unsafePatterns() {
            for (String pattern : List.of("*", "*.com", "*.co.uk", "*.github.io", "*.blob.core.windows.net", "api.*.com")) {
                assertThrows(IllegalStateException.class, () -> EgressProxy.of(URL, List.of(pattern)), pattern);
            }
        }
    }
}
