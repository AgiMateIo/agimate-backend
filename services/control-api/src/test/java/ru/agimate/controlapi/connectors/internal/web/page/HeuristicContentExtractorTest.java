package ru.agimate.controlapi.connectors.internal.web.page;

import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.internal.web.WebFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HeuristicContentExtractor")
class HeuristicContentExtractorTest {

    private final HeuristicContentExtractor extractor = new HeuristicContentExtractor();

    @Test
    @DisplayName("новость: берётся article, шапка, меню, «поделиться» и комментарии уходят")
    void news() {
        ExtractedContent content = extractor.extract(WebFixtures.document("news.html"));

        assertEquals("ЦБ сохранил ключевую ставку — Новости", content.title());
        String text = content.body().text();
        assertTrue(text.contains("Совет директоров Банка России"));
        assertTrue(text.contains("ЦБ сохранил ключевую ставку"), "заголовок статьи внутри article остаётся");
        assertFalse(text.contains("Поделиться"));
        assertFalse(text.contains("Раздел А"));
        assertFalse(text.contains("Комментарий пользователя"));
        assertFalse(text.contains("Популярное"));
    }

    @Test
    @DisplayName("метаданные: description, article:*, og:type, дата из JSON-LD статьи")
    void newsMetadata() {
        ExtractedContent content = extractor.extract(WebFixtures.document("news.html"));

        assertEquals("Банк России оставил ключевую ставку без изменений.", content.metadata().get("description"));
        assertEquals("2026-09-12T13:30:00+03:00", content.metadata().get("article:published_time"));
        assertEquals("article", content.metadata().get("og:type"));
        assertFalse(content.metadata().containsKey("og:title"), "og:title повторяет title");
        assertEquals("2026-09-12T13:30:00+03:00", content.metadata().get("published"));
        assertFalse(content.metadata().containsKey("json-ld"), "JSON-LD статьи повторяет текст — из него берутся только дата и автор");
    }

    @Test
    @DisplayName("блог без article: блок выбирается по плотности текста, сайдбар и комментарии уходят")
    void blogByDensity() {
        ExtractedContent content = extractor.extract(WebFixtures.document("blog.html"));

        String text = content.body().text();
        assertTrue(text.contains("Виртуальные потоки появились в Java 21"));
        assertTrue(text.contains("newVirtualThreadPerTaskExecutor"));
        assertFalse(text.contains("Лучшее за неделю"));
        assertFalse(text.contains("Отличная статья"));
        assertFalse(text.contains("Лента"));
    }

    @Test
    @DisplayName("SPA карточки товара: тела нет, цена и рейтинг — в метаданных")
    void productWithoutBody() {
        ExtractedContent content = extractor.extract(WebFixtures.document("product.html"));

        assertNull(content.body());
        assertEquals("Кофемашина Орион КМ-200 — купить в магазине", content.title());
        assertEquals("24990", content.metadata().get("product:price:amount"));
        String jsonLd = content.metadata().get("json-ld");
        assertTrue(jsonLd.contains("\"price\":\"24990\""));
        assertTrue(jsonLd.contains("InStock"));
        assertTrue(jsonLd.contains("\"ratingValue\":\"4.7\""));
        assertFalse(jsonLd.contains("review\""), "отзывы длинные и в данные не входят");
        assertFalse(jsonLd.contains("@context"));
    }

    @Test
    @DisplayName("пустая оболочка SPA — ни тела, ни заголовка, ни метаданных")
    void emptyShell() {
        ExtractedContent content = extractor.extract(WebFixtures.document("spa-empty.html"));

        assertNull(content.body());
        assertNull(content.title());
        assertTrue(content.metadata().isEmpty());
    }

    @Test
    @DisplayName("исходный документ не меняется")
    void leavesDocumentIntact() {
        Document document = WebFixtures.document("news.html");
        String before = document.outerHtml();

        ExtractedContent content = extractor.extract(document);

        assertNotNull(content.body());
        assertEquals(before, document.outerHtml());
    }
}
