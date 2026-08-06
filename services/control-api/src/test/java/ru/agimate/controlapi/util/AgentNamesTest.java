package ru.agimate.controlapi.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("AgentNames.unique — имя нового агента")
class AgentNamesTest {

    @Test
    @DisplayName("имя свободно — остаётся как есть")
    void keepsFreeName() {
        assertEquals("Внешний ИИ", AgentNames.unique("Внешний ИИ", List.of("Ассистент")));
    }

    @Test
    @DisplayName("занято — (2), потом (3)")
    void appendsCounter() {
        assertEquals("Внешний ИИ (2)", AgentNames.unique("Внешний ИИ", Set.of("Внешний ИИ")));
        assertEquals("Внешний ИИ (3)", AgentNames.unique("Внешний ИИ", Set.of("Внешний ИИ", "Внешний ИИ (2)")));
    }

    @Test
    @DisplayName("дырка в нумерации занимается первой свободной")
    void fillsTheGap() {
        assertEquals("Внешний ИИ (2)",
                AgentNames.unique("Внешний ИИ", Set.of("Внешний ИИ", "Внешний ИИ (3)")));
    }

    @Test
    @DisplayName("счётчик в присланном имени не удваивается")
    void doesNotStackCounters() {
        assertEquals("Внешний ИИ (3)",
                AgentNames.unique("Внешний ИИ (2)", Set.of("Внешний ИИ", "Внешний ИИ (2)")));
    }

    @Test
    @DisplayName("имя, которое целиком выглядит счётчиком, не срезается в пустоту")
    void keepsNameThatLooksLikeACounter() {
        assertEquals("(2)", AgentNames.unique("(2)", Set.of()));
    }

    @Test
    @DisplayName("пробелы по краям срезаются")
    void trims() {
        assertEquals("Ассистент", AgentNames.unique("  Ассистент  ", Set.of()));
    }
}
