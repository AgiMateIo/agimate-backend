package ru.agimate.controlapi.connectors.internal.divination;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DivinationToolService: 4 тула через диспатч")
class DivinationToolServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    private DivinationConnectorService handler;
    private ConnectorEnv env;

    @BeforeEach
    void setUp() {
        handler = new DivinationConnectorService(new DivinationToolService());
        env = new ConnectorEnv(null, USER_ID, AGENT_ID, null, null, null, Map.of(), null);
    }

    @Test
    @DisplayName("matrix_of_destiny: контрольные значения 14.08.1990")
    void matrixOfDestiny() {
        Map<String, Object> result = handler.executeTool(env, "matrix_of_destiny",
                Map.of("birthDate", "1990-08-14"));

        Map<?, ?> points = (Map<?, ?>) result.get("points");
        assertEquals(14, points.get("day"));
        assertEquals(5, points.get("mission"));
        assertEquals(10, points.get("center"));
        assertEquals(List.of(19, 6, 5), result.get("karmicTail"));
        assertEquals(7, ((Map<?, ?>) result.get("moneyLine")).get("money"));
    }

    @Test
    @DisplayName("numerology: lifePath и мастер-компоненты")
    void numerology() {
        Map<String, Object> result = handler.executeTool(env, "numerology",
                Map.of("birthDate", "1993-11-29"));

        Map<?, ?> lifePath = (Map<?, ?>) result.get("lifePath");
        assertEquals(8, lifePath.get("value"));
        assertEquals(false, lifePath.get("isMaster"));
        assertEquals(11, ((Map<?, ?>) lifePath.get("components")).get("day"));
        assertEquals(11, result.get("birthdayNumber"));
        assertNotNull(result.get("personalYear"));
    }

    @Test
    @DisplayName("tarot_card_of_day: детерминирована в пределах дня, требует userId")
    void tarotCardOfDay() {
        Map<String, Object> args = Map.of("date", "2026-07-17");
        Map<String, Object> first = handler.executeTool(env, "tarot_card_of_day", args);
        Map<String, Object> second = handler.executeTool(env, "tarot_card_of_day", args);

        assertEquals(first.get("card"), second.get("card"));
        assertEquals(true, first.get("sameForWholeDay"));
        assertNotNull(((Map<?, ?>) first.get("card")).get("keywords"));

        ConnectorEnv noUser = new ConnectorEnv(null, null, AGENT_ID, null, null, null, Map.of(), null);
        assertThrows(ConnectorException.class,
                () -> handler.executeTool(noUser, "tarot_card_of_day", args));
    }

    @Test
    @DisplayName("tarot_draw_spread: расклад с позициями; enum приходит строкой из args")
    void tarotDrawSpread() {
        Map<String, Object> result = handler.executeTool(env, "tarot_draw_spread",
                Map.of("spread", "THREE_CARD", "question", "Как пройдёт запуск?"));

        assertEquals("THREE_CARD", result.get("spread"));
        assertEquals("Как пройдёт запуск?", result.get("question"));
        List<?> cards = (List<?>) result.get("cards");
        assertEquals(3, cards.size());
        assertEquals("past", ((Map<?, ?>) cards.getFirst()).get("position"));
        assertTrue(cards.stream().allMatch(c -> ((Map<?, ?>) c).get("nameRu") != null));
    }

    @Test
    @DisplayName("Невалидная дата → ConnectorException")
    void invalidDate() {
        assertThrows(ConnectorException.class, () -> handler.executeTool(env, "matrix_of_destiny",
                Map.of("birthDate", "14.08.1990")));
        assertThrows(ConnectorException.class, () -> handler.executeTool(env, "numerology",
                Map.of("birthDate", "not-a-date")));
    }
}
