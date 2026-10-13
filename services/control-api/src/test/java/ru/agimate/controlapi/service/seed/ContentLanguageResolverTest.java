package ru.agimate.controlapi.service.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ru.agimate.controlapi.config.ContentProperties;
import ru.agimate.controlapi.database.enums.ContentLanguage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static ru.agimate.controlapi.service.seed.ContentLanguageResolver.fromAcceptLanguage;

@DisplayName("ContentLanguageResolver")
class ContentLanguageResolverTest {

    @Nested
    @DisplayName("Accept-Language")
    class Header {

        @Test
        @DisplayName("нет заголовка или он пуст — дефолт")
        void absentIsDefault() {
            assertEquals(ContentLanguage.RU, fromAcceptLanguage(null, ContentLanguage.RU));
            assertEquals(ContentLanguage.RU, fromAcceptLanguage(" ", ContentLanguage.RU));
        }

        @Test
        @DisplayName("неподдерживаемый язык пропускается, берётся следующий поддерживаемый по весу")
        void skipsUnsupported() {
            assertEquals(ContentLanguage.EN, fromAcceptLanguage("es-ES,es;q=0.9,en;q=0.8,ru;q=0.7", ContentLanguage.RU));
        }

        @Test
        @DisplayName("только неподдерживаемые — дефолт")
        void onlyUnsupportedIsDefault() {
            assertEquals(ContentLanguage.RU, fromAcceptLanguage("es-ES,es;q=0.9", ContentLanguage.RU));
        }

        @Test
        @DisplayName("региональная форма матчится по основному сабтегу")
        void regionalMatchesPrimary() {
            assertEquals(ContentLanguage.EN, fromAcceptLanguage("en-US", ContentLanguage.RU));
        }

        @Test
        @DisplayName("решает вес, а не порядок в заголовке")
        void weightWins() {
            assertEquals(ContentLanguage.RU, fromAcceptLanguage("en;q=0.5,ru", ContentLanguage.EN));
        }

        @Test
        @DisplayName("q=0 — отказ от языка")
        void zeroWeightIsRefusal() {
            assertEquals(ContentLanguage.RU, fromAcceptLanguage("en;q=0", ContentLanguage.RU));
        }

        @Test
        @DisplayName("битый заголовок — дефолт, а не ошибка")
        void malformedIsDefault() {
            assertEquals(ContentLanguage.RU, fromAcceptLanguage("en;q=abc", ContentLanguage.RU));
        }
    }

    @Nested
    @DisplayName("requestLanguage")
    class Request {

        private ContentLanguageResolver resolver() {
            ContentProperties properties = new ContentProperties();
            properties.setLanguage(ContentLanguage.RU);
            return new ContentLanguageResolver(properties);
        }

        @Test
        @DisplayName("вне HTTP-запроса — дефолт")
        void outsideRequestIsDefault() {
            RequestContextHolder.resetRequestAttributes();

            assertEquals(ContentLanguage.RU, resolver().requestLanguage());
        }

        @Test
        @DisplayName("в запросе — язык из заголовка")
        void readsHeader() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Accept-Language", "en-US,en;q=0.9");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            try {
                assertEquals(ContentLanguage.EN, resolver().requestLanguage());
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        }
    }
}
