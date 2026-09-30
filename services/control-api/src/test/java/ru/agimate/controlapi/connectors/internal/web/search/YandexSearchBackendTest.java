package ru.agimate.controlapi.connectors.internal.web.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.internal.web.WebFixtures;
import ru.agimate.controlapi.connectors.internal.web.WebProperties;
import ru.agimate.controlapi.service.http.PublicOnlyHttp;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("YandexSearchBackend")
class YandexSearchBackendTest {

    @Test
    @DisplayName("разбирает выдачу: подсветка снята, пассажи склеены, modtime — дата")
    void parsesResults() {
        List<WebSearchHit> hits = YandexSearchBackend.parse(WebFixtures.text("yandex-response.xml"), 10);

        assertEquals(3, hits.size());
        WebSearchHit first = hits.get(0);
        assertEquals("Ключевая ставка Банка России", first.title());
        assertEquals("https://cbr.ru/hd_base/KeyRate/", first.url());
        assertEquals("Решение о ключевой ставке принято 12 сентября. … Текущее значение — 16%.", first.snippet());
        assertEquals("2026-09-12", first.date());
    }

    @Test
    @DisplayName("без пассажей сниппет — headline, без modtime — даты нет")
    void headlineFallback() {
        WebSearchHit second = YandexSearchBackend.parse(WebFixtures.text("yandex-response.xml"), 10).get(1);

        assertEquals("Сводка по банкам.", second.snippet());
        assertNull(second.date());
    }

    @Test
    @DisplayName("не больше limit результатов")
    void respectsLimit() {
        assertEquals(2, YandexSearchBackend.parse(WebFixtures.text("yandex-response.xml"), 2).size());
    }

    @Test
    @DisplayName("ошибка 15 «ничего не найдено» — пустой список, другая ошибка — ConnectorException")
    void errors() {
        String nothing = "<yandexsearch><response><error code=\"15\">Искомая комбинация слов нигде не встречается</error></response></yandexsearch>";
        assertTrue(YandexSearchBackend.parse(nothing, 10).isEmpty());

        String broken = "<yandexsearch><response><error code=\"31\">Сервис недоступен</error></response></yandexsearch>";
        ConnectorException e = assertThrows(ConnectorException.class, () -> YandexSearchBackend.parse(broken, 10));
        assertTrue(e.getMessage().contains("31"));
    }

    @Test
    @DisplayName("DOCTYPE в ответе отвергается — никаких внешних сущностей")
    void refusesDoctype() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>";
        assertThrows(ConnectorException.class, () -> YandexSearchBackend.parse(xxe, 10));
    }

    @Test
    @DisplayName("без ключа — понятный отказ, без похода в сеть")
    void notConfigured() {
        var backend = new YandexSearchBackend(new WebProperties(), new PublicOnlyHttp(false));

        ConnectorException e = assertThrows(ConnectorException.class,
                () -> backend.search(new WebSearchQuery("что-то", 10, 225)));
        assertTrue(e.getMessage().contains("not configured"));
    }
}
