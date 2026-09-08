package ru.agimate.controlapi.service.runcontext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Summaries")
class SummariesTest {

    @Test
    @DisplayName("first sentence of a multi-paragraph MCP description, nothing past the line break")
    void firstSentence() {
        String description = "Search train tickets between two cities. Returns a list of trips.\n\n"
                + "## Parameters\n- from: city code\n- to: city code";
        assertEquals("Search train tickets between two cities.", Summaries.of(description));
        assertEquals("Search tickets", Summaries.of("Search tickets\n\nlong text"));
        assertEquals("No terminal punctuation at all", Summaries.of("No terminal punctuation at all"));
    }

    @Test
    @DisplayName("a long sentence is cut at a word boundary under the cap, with an ellipsis")
    void capped() {
        String summary = Summaries.of("word ".repeat(100).strip() + ".");
        assertTrue(summary.length() <= Summaries.MAX_SUMMARY_CHARS);
        assertTrue(summary.endsWith("…"));
        assertTrue(summary.endsWith("word…"));
    }

    @Test
    @DisplayName("blank or null description gives an empty line, never null")
    void blank() {
        assertEquals("", Summaries.of(null));
        assertEquals("", Summaries.of("   "));
    }
}
