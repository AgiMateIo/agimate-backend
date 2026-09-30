package ru.agimate.controlapi.connectors.internal.web.page;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.internal.web.WebFixtures;
import ru.agimate.controlapi.service.http.PublicOnlyHttp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("WebPageReader")
class WebPageReaderTest {

    @Nested
    @DisplayName("по HTTP")
    class OverHttp {

        private HttpServer server;
        private String base;
        private WebPageReader reader;

        @BeforeEach
        void setUp() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            serve("/news", 200, "text/html; charset=utf-8", WebFixtures.bytes("news.html"));
            serve("/old", 200, "text/html", WebFixtures.bytes("cp1251.html"));
            serve("/plain", 200, "text/plain; charset=windows-1251", "Привет, мир".getBytes(Charset.forName("windows-1251")));
            serve("/pdf", 200, "application/pdf", new byte[]{'%', 'P', 'D', 'F'});
            serve("/wall", 403, "text/html; charset=utf-8", WebFixtures.bytes("challenge.html"));
            serve("/gone", 404, "text/html; charset=utf-8", "<p>not found</p>".getBytes(StandardCharsets.UTF_8));
            serve("/spa", 200, "text/html; charset=utf-8", WebFixtures.bytes("spa-empty.html"));
            serve("/long", 200, "text/html; charset=utf-8", longPage().getBytes(StandardCharsets.UTF_8));
            redirect("/moved", "/news");
            redirect("/loop", "/loop");
            server.start();
            base = "http://127.0.0.1:" + server.getAddress().getPort();
            reader = new WebPageReader(new PublicOnlyHttp(true), new HeuristicContentExtractor(), new JsoupMarkdownConverter());
        }

        @AfterEach
        void tearDown() {
            server.stop(0);
        }

        @Test
        @DisplayName("страница: метаданные первыми, затем текст статьи в markdown")
        void readsPage() {
            WebPage page = reader.read(base + "/news", 20_000);

            assertEquals("ЦБ сохранил ключевую ставку — Новости", page.title());
            assertFalse(page.truncated());
            String content = page.content();
            assertTrue(content.startsWith("Page metadata:\n- description: Банк России"), content);
            assertTrue(content.contains("# ЦБ сохранил ключевую ставку"));
            assertTrue(content.contains("[обзоре ставок](" + base + "/economy/rates)"), content);
            assertFalse(content.contains("Комментарий пользователя"));
        }

        @Test
        @DisplayName("windows-1251 без charset в заголовке — по meta")
        void legacyCharset() {
            WebPage page = reader.read(base + "/old", 20_000);

            assertEquals("Старый сайт: расписание электричек", page.title());
            assertTrue(page.content().contains("Расписание пригородных поездов"), page.content());
        }

        @Test
        @DisplayName("text/plain в charset из заголовка")
        void plainText() {
            WebPage page = reader.read(base + "/plain", 20_000);

            assertEquals("Привет, мир", page.content());
            assertNull(page.title());
        }

        @Test
        @DisplayName("редирект проходится, url — конечный")
        void followsRedirect() {
            WebPage page = reader.read(base + "/moved", 20_000);

            assertEquals(base + "/news", page.url());
            assertTrue(page.content().contains("Совет директоров"));
        }

        @Test
        @DisplayName("цикл редиректов обрывается")
        void redirectLoop() {
            ConnectorException e = assertThrows(ConnectorException.class, () -> reader.read(base + "/loop", 20_000));
            assertTrue(e.getMessage().contains("Too many redirects"));
        }

        @Test
        @DisplayName("PDF отклоняется понятной ошибкой")
        void unsupportedType() {
            ConnectorException e = assertThrows(ConnectorException.class, () -> reader.read(base + "/pdf", 20_000));
            assertTrue(e.getMessage().contains("application/pdf"));
        }

        @Test
        @DisplayName("антибот-стена на 403 — отдельная ошибка, обычный 404 — статус")
        void walls() {
            ConnectorException wall = assertThrows(ConnectorException.class, () -> reader.read(base + "/wall", 20_000));
            assertTrue(wall.getMessage().contains("anti-bot"));

            ConnectorException gone = assertThrows(ConnectorException.class, () -> reader.read(base + "/gone", 20_000));
            assertEquals("The page answered HTTP 404", gone.getMessage());
        }

        @Test
        @DisplayName("пустая оболочка SPA — ошибка «нет текста»")
        void emptyShell() {
            ConnectorException e = assertThrows(ConnectorException.class, () -> reader.read(base + "/spa", 20_000));
            assertTrue(e.getMessage().contains("no readable text"));
        }

        @Test
        @DisplayName("длинная страница режется по абзацу")
        void truncates() {
            WebPage page = reader.read(base + "/long", 2_000);

            assertTrue(page.truncated());
            assertTrue(page.content().length() <= 2_000);
            assertTrue(page.content().endsWith("."), page.content());
        }

        @Test
        @DisplayName("приватный адрес без разрешения — отказ")
        void privateTargetRefused() {
            var guarded = new WebPageReader(new PublicOnlyHttp(false), new HeuristicContentExtractor(), new JsoupMarkdownConverter());

            ConnectorException e = assertThrows(ConnectorException.class, () -> guarded.read(base + "/news", 20_000));
            assertTrue(e.getMessage().contains("cannot be fetched") || e.getMessage().contains("not allowed"), e.getMessage());
        }

        private void serve(String path, int status, String contentType, byte[] body) {
            server.createContext(path, exchange -> {
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
        }

        private void redirect(String path, String location) {
            server.createContext(path, exchange -> {
                exchange.getResponseHeaders().set("Location", location);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
        }

        private static String longPage() {
            StringBuilder html = new StringBuilder("<html><head><title>Длинная</title></head><body><article>");
            for (int i = 0; i < 100; i++) {
                html.append("<p>Абзац номер ").append(i).append(", в котором достаточно текста, чтобы страница была длинной.</p>");
            }
            return html.append("</article></body></html>").toString();
        }
    }

    @Nested
    @DisplayName("без сети")
    class Offline {

        @Test
        @DisplayName("обрезка: по абзацу, иначе по строке, иначе жёстко")
        void cut() {
            assertEquals(new WebPageReader.Cut("короткий", false), WebPageReader.cut("короткий", 100));
            assertEquals(new WebPageReader.Cut("aaaa\n\nbbbb", true), WebPageReader.cut("aaaa\n\nbbbb\n\ncccc", 12));
            assertEquals(new WebPageReader.Cut("aaaaaa\nbbb", true), WebPageReader.cut("aaaaaa\nbbb\ncc", 11));
            assertEquals(new WebPageReader.Cut("abcde", true), WebPageReader.cut("abcdefgh", 5));
        }

        @Test
        @DisplayName("кириллический домен — в punycode, кириллица в пути — в percent-encoding")
        void toAscii() {
            assertEquals("https://xn--d1acufc.xn--p1ai/%D0%BF%D1%83%D1%82%D1%8C?q=%D0%B0",
                    WebPageReader.toAscii("https://домен.рф/путь?q=а"));
            assertEquals("https://example.ru:8080/a?b=c", WebPageReader.toAscii("https://example.ru:8080/a?b=c"));
        }

        @Test
        @DisplayName("стена узнаётся по разметке, статья про капчу — нет")
        void challenge() {
            assertTrue(WebPageReader.isChallenge(WebFixtures.document("challenge.html")));
            assertFalse(WebPageReader.isChallenge(WebFixtures.document("news.html")));

            StringBuilder article = new StringBuilder("<html><body><h1>Как работает captcha</h1>");
            article.append("<p>").append("Длинный текст о капче. ".repeat(200)).append("</p></body></html>");
            assertFalse(WebPageReader.isChallenge(org.jsoup.Jsoup.parse(article.toString())));
        }
    }
}
