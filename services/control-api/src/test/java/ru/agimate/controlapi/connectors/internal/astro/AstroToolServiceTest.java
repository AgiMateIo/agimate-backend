package ru.agimate.controlapi.connectors.internal.astro;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AstroToolService: тулы natal_chart / transits / synastry через диспатч")
class AstroToolServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    private AstroConnectorService handler;
    private ConnectorEnv env;

    @BeforeEach
    void setUp() {
        handler = new AstroConnectorService(new AstroToolService());
        env = new ConnectorEnv(null, USER_ID, AGENT_ID, null, null, null, Map.of(), null);
    }

    private Map<String, Object> call(String tool, Map<String, Object> args) {
        return handler.executeTool(env, tool, args);
    }

    @Nested
    @DisplayName("natal_chart")
    class NatalChart {

        @Test
        @DisplayName("Без времени: timeKnown=false, углы и дома null, Луна помечена uncertain")
        void withoutTime() {
            Map<String, Object> result = call("natal_chart", Map.of("birthDate", "1990-08-14"));

            assertEquals(false, result.get("timeKnown"));
            assertTrue(result.containsKey("angles"));
            assertNull(result.get("angles"));
            assertNull(result.get("houses"));
            List<?> notes = (List<?>) result.get("notes");
            assertEquals(1, notes.size());

            List<Map<String, Object>> planets = planets(result);
            Map<String, Object> moon = planets.get(1);
            assertEquals("Moon", moon.get("body"));
            assertEquals(true, moon.get("uncertain"));
            assertFalse(planets.getFirst().containsKey("house"));
        }

        @Test
        @DisplayName("birthTime без tzid → ConnectorException")
        void timeWithoutTzid() {
            Map<String, Object> args = Map.of("birthDate", "1990-08-14", "birthTime", "10:30");
            assertThrows(ConnectorException.class, () -> call("natal_chart", args));
        }

        @Test
        @DisplayName("Полный вход: 12 куспидов Whole Sign, дом Солнца согласован с асцендентом")
        void fullInput() {
            Map<String, Object> result = call("natal_chart", fullBirthArgs());

            assertEquals(true, result.get("timeKnown"));
            assertEquals("tropical", result.get("zodiacType"));

            Map<String, Object> houses = cast(result.get("houses"));
            assertEquals("whole_sign", houses.get("system"));
            assertEquals(12, ((List<?>) houses.get("cusps")).size());

            Map<String, Object> angles = cast(result.get("angles"));
            Map<String, Object> ascendant = cast(angles.get("ascendant"));
            assertNotNull(ascendant.get("sign"));

            // Солнце 14.08.1990 — Лев; дом должен соответствовать смещению Льва от знака ASC
            Map<String, Object> sun = planets(result).getFirst();
            assertEquals("Leo", sun.get("sign"));
            assertNotNull(sun.get("house"));
            assertTrue(((List<?>) result.get("aspects")).stream().allMatch(Map.class::isInstance));

            Map<String, Object> nodes = cast(result.get("lunarNodes"));
            assertEquals("mean", nodes.get("type"));
            assertNotNull(cast(nodes.get("northNode")).get("sign"));
        }

        @Test
        @DisplayName("Невалидная дата → ConnectorException")
        void invalidDate() {
            assertThrows(ConnectorException.class,
                    () -> call("natal_chart", Map.of("birthDate", "14.08.1990")));
        }
    }

    @Nested
    @DisplayName("transits")
    class Transits {

        @Test
        @DisplayName("Без аргументов: позиции на сейчас, без натальной части")
        void bareSky() {
            Map<String, Object> result = call("transits", Map.of());
            assertEquals(10, planets(result).size());
            assertFalse(result.containsKey("transitAspects"));
        }

        @Test
        @DisplayName("С данными рождения: есть natalPlanets и transitAspects")
        void withNatal() {
            Map<String, Object> args = new HashMap<>(fullBirthArgs());
            args.put("date", "2026-07-17");
            Map<String, Object> result = call("transits", args);

            assertNotNull(result.get("natalPlanets"));
            assertNotNull(result.get("transitAspects"));
        }
    }

    @Test
    @DisplayName("synastry: обе карты и interAspects")
    void synastry() {
        Map<String, Object> result = call("synastry", Map.of(
                "firstBirthDate", "1990-08-14",
                "secondBirthDate", "1992-03-01"));

        Map<String, Object> first = cast(result.get("first"));
        assertEquals("Leo", first.get("sunSign"));
        assertNotNull(cast(result.get("second")).get("sunSign"));
        assertNotNull(result.get("interAspects"));
        // Обе даты без времени — примечания для обеих персон
        assertEquals(2, ((List<?>) result.get("notes")).size());
    }

    private static Map<String, Object> fullBirthArgs() {
        return Map.of(
                "birthDate", "1990-08-14",
                "birthTime", "10:30",
                "tzid", "Europe/Moscow",
                "latitude", 55.75,
                "longitude", 37.62);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> planets(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("planets");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
