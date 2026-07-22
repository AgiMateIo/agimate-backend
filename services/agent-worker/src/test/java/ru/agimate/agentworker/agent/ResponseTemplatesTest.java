package ru.agimate.agentworker.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import ru.agimate.agentworker.config.AgentProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseTemplatesTest {

    private static ResponseTemplates templates(String lang) {
        ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
        ms.setBasename("messages");
        ms.setDefaultEncoding("UTF-8");
        // Mirror application.yaml: unknown language → base bundle, not the JVM locale.
        ms.setFallbackToSystemLocale(false);
        AgentProperties props = new AgentProperties();
        props.getResponse().setLanguage(lang);
        return new ResponseTemplates(ms, props);
    }

    @Test
    @DisplayName("en и ru резолвятся из разных бандлов (переводы отличаются)")
    void resolvesPerLanguage() {
        assertTrue(templates("en").maxTurns().startsWith("Sorry"));
        assertTrue(templates("ru").maxTurns().startsWith("Извини"));
        assertNotEquals(templates("en").filtered(), templates("ru").filtered());
    }

    @Test
    @DisplayName("неизвестный язык → базовый бандл (английский)")
    void unknownLanguageFallsBackToBase() {
        assertEquals(templates("en").infraError(), templates("de").infraError());
    }
}
