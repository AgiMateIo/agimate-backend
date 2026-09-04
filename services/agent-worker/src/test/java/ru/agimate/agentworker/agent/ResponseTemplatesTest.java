package ru.agimate.agentworker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseTemplatesTest {

    private static ResponseTemplates templates(String lang) {
        return TestTemplates.of(lang);
    }

    @Test
    @DisplayName("en и ru резолвятся из разных бандлов (переводы отличаются)")
    void resolvesPerLanguage() {
        assertTrue(templates("en").maxTurns().startsWith("Sorry"));
        assertTrue(templates("ru").maxTurns().startsWith("Извини"));
        assertNotEquals(templates("en").filtered(), templates("ru").filtered());
        assertNotEquals(templates("en").noModel(), templates("ru").noModel());
        // Model-facing тоже живёт в бандле — иначе он был бы захардкожен на одном языке.
        assertNotEquals(templates("en").wrapUp(), templates("ru").wrapUp());
    }

    @Test
    @DisplayName("тег блока подставляется в преамбулу и guidance; фигурные скобки в detached-тексте целы")
    void promptTextsTakeTheirArguments() {
        assertTrue(templates("en").untrustedPreamble("mail").contains("<mail>"));
        assertTrue(templates("ru").toolOutputGuidance("untrusted_tool_output").contains("<untrusted_tool_output>"));
        assertTrue(templates("en").detachedToolGuidance().contains("{\"status\":\"detached\""));
    }

    @Test
    @DisplayName("неизвестный язык → базовый бандл (английский)")
    void unknownLanguageFallsBackToBase() {
        assertEquals(templates("en").infraError(), templates("de").infraError());
    }
}
