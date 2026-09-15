package ru.agimate.controlapi.connectors.internal.files;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TextFiles — тип по имени, текстовость, строки для чтения")
class TextFilesTest {

    @Test
    @DisplayName("mime по расширению, всё прочее — text/plain")
    void mimeForName() {
        assertEquals("text/html", TextFiles.mimeForName("Report.HTML"));
        assertEquals("text/markdown", TextFiles.mimeForName("notes.md"));
        assertEquals("application/json", TextFiles.mimeForName("data.json"));
        assertEquals("text/plain", TextFiles.mimeForName("script.py"));
        assertEquals("text/plain", TextFiles.mimeForName("README"));
        assertEquals("text/plain", TextFiles.mimeForName(null));
    }

    @Test
    @DisplayName("текст — text/*, json/xml и их +-суффиксы, с параметрами тоже")
    void isText() {
        assertTrue(TextFiles.isText("text/plain;charset=UTF-8"));
        assertTrue(TextFiles.isText("application/json"));
        assertTrue(TextFiles.isText("image/svg+xml"));
        assertFalse(TextFiles.isText("image/png"));
        assertFalse(TextFiles.isText("application/pdf"));
        assertFalse(TextFiles.isText("not a mime"));
    }

    @Test
    @DisplayName("длинная строка режется на куски, завершающий перенос не даёт пустой строки")
    void lines() {
        String longLine = "x".repeat(TextFiles.MAX_LINE_CHARS + 5);
        List<String> lines = TextFiles.lines("a\r\n" + longLine + "\n");

        assertEquals(3, lines.size());
        assertEquals("a", lines.get(0));
        assertEquals(TextFiles.MAX_LINE_CHARS, lines.get(1).length());
        assertEquals("xxxxx", lines.get(2));
        assertEquals(List.of(""), TextFiles.lines(""));
    }
}
