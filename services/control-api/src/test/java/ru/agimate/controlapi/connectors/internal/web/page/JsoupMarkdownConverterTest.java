package ru.agimate.controlapi.connectors.internal.web.page;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.internal.web.WebFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("JsoupMarkdownConverter")
class JsoupMarkdownConverterTest {

    private final JsoupMarkdownConverter converter = new JsoupMarkdownConverter();

    private String convert(String html) {
        Element body = Jsoup.parse(html, "https://example.ru/docs/page").body();
        return converter.convert(body);
    }

    @Test
    @DisplayName("заголовки, абзацы и выделение")
    void headingsAndParagraphs() {
        assertEquals("# Заголовок\n\nПервый **жирный** и *курсив* абзац.\n\n## Раздел\n\nВторой `код`.",
                convert("<h1>Заголовок</h1><p>Первый <b>жирный</b> и <em>курсив</em>   абзац.</p>"
                        + "<h2>Раздел</h2><p>Второй <code>код</code>.</p>"));
    }

    @Test
    @DisplayName("ссылки абсолютные; якоря и javascript: — только текст")
    void links() {
        assertEquals("[Обзор](https://example.ru/docs/rates), якорь и кнопка.",
                convert("<p><a href=\"rates\">Обзор</a>, <a href=\"#top\">якорь</a> и "
                        + "<a href=\"javascript:void(0)\">кнопка</a>.</p>"));
    }

    @Test
    @DisplayName("вложенные списки с отступом, нумерация с start")
    void lists() {
        assertEquals("3. Первый\n4. Второй\n   - вложенный\n   - ещё",
                convert("<ol start=\"3\"><li>Первый</li><li>Второй<ul><li>вложенный</li><li>ещё</li></ul></li></ol>"));
    }

    @Test
    @DisplayName("простая таблица — GFM")
    void plainTable() {
        assertEquals("| Вариант | RPS |\n| --- | --- |\n| Виртуальные | 9 800 |",
                convert("<table><tr><th>Вариант</th><th>RPS</th></tr><tr><td>Виртуальные</td><td>9 800</td></tr></table>"));
    }

    @Test
    @DisplayName("таблица с объединёнными ячейками — построчно")
    void spannedTable() {
        assertEquals("Итого\nА | Б",
                convert("<table><tr><td colspan=\"2\">Итого</td></tr><tr><td>А</td><td>Б</td></tr></table>"));
    }

    @Test
    @DisplayName("таблица в одну колонку — вёрстка, а не данные")
    void layoutTable() {
        assertEquals("Текст в ячейке.\n\nВторой абзац.",
                convert("<table><tr><td><p>Текст в ячейке.</p><p>Второй абзац.</p></td></tr></table>"));
    }

    @Test
    @DisplayName("код с языком, цитата, картинка — alt")
    void codeQuoteImage() {
        String markdown = convert("<pre><code class=\"language-java\">int a = 1;\n  int b = 2;</code></pre>"
                + "<blockquote><p>Цитата.</p><p>Вторая.</p></blockquote><p><img src=\"x.png\" alt=\"График\"></p>");

        assertEquals("```java\nint a = 1;\n  int b = 2;\n```\n\n> Цитата.\n>\n> Вторая.\n\nГрафик", markdown);
    }

    @Test
    @DisplayName("смешанный контейнер: текст вне тегов становится абзацами")
    void mixedContent() {
        assertEquals("Начало строки\n\nАбзац\n\nхвост", convert("<div>Начало <span>строки</span><p>Абзац</p>хвост</div>"));
    }

    @Test
    @DisplayName("статья блога целиком читается")
    void blogFixture() {
        String markdown = converter.convert(WebFixtures.document("blog.html").selectFirst("#post-content-body"));

        assertTrue(markdown.contains("```java\ntry (var executor"));
        assertTrue(markdown.contains("**не пинить**"));
        assertTrue(markdown.contains("1. Замерьте"));
        assertTrue(markdown.contains("| Платформенные | 1 200 |"));
        assertTrue(markdown.contains("> Виртуальный поток"));
        assertFalse(markdown.contains("<"), markdown);
    }
}
