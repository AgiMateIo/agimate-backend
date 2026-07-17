package ru.agimate.controlapi.connectors.internal.astro.calc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ChartAngles: асцендент и MC")
class ChartAnglesTest {

    @Nested
    @DisplayName("Аналитические квадранты (fromSidereal)")
    class Quadrants {

        @Test
        @DisplayName("θ=0, φ=0, ε=0 → MC=0° (Овен кульминирует), ASC=90° (0° Рака восходит)")
        void thetaZero() {
            ChartAngles angles = ChartAngles.fromSidereal(0, 0, 0);
            assertEquals(0.0, angles.midheaven(), 1e-9);
            assertEquals(90.0, angles.ascendant(), 1e-9);
        }

        @Test
        @DisplayName("θ=90, φ=0, ε=0 → MC=90°, ASC=180°")
        void thetaNinety() {
            ChartAngles angles = ChartAngles.fromSidereal(90, 0, 0);
            assertEquals(90.0, angles.midheaven(), 1e-9);
            assertEquals(180.0, angles.ascendant(), 1e-9);
        }

        @Test
        @DisplayName("Реальный ε: ASC всегда ровно на 90° позже MC по часовому кругу на экваторе θ=0")
        void equatorRealObliquity() {
            ChartAngles angles = ChartAngles.fromSidereal(0, 23.4393, 0);
            assertEquals(0.0, angles.midheaven(), 1e-9);
            assertEquals(90.0, angles.ascendant(), 1e-9); // при θ=0 точка 0° Рака на востоке независимо от ε
        }
    }

    @Test
    @DisplayName("Эталон: 2000-01-01 12:00 UTC, Москва — MC≈315.6° (Водолей), ASC≈87.8° (Близнецы)")
    void moscowJ2000() {
        // Эталон выведен независимо от библиотеки: опубликованный GMST на эпоху J2000
        // (18h41m50.548s = 280.4606°) + долгота Москвы 37.6173°E → RAMC 318.078°;
        // ε=23.4393°, φ=55.7558° → MC=315.57°, ASC=87.77° (сферическая тригонометрия).
        ChartAngles angles = ChartAngles.compute(
                Instant.parse("2000-01-01T12:00:00Z"), 55.7558, 37.6173);
        assertEquals(315.57, angles.midheaven(), 0.5);
        assertEquals(87.77, angles.ascendant(), 0.5);
        assertEquals(ZodiacSign.AQUARIUS, ZodiacSign.fromLongitude(angles.midheaven()));
        assertEquals(ZodiacSign.GEMINI, ZodiacSign.fromLongitude(angles.ascendant()));
    }

    @Test
    @DisplayName("За полярным кругом — ConnectorException")
    void polarLatitude() {
        assertThrows(ConnectorException.class, () -> ChartAngles.compute(
                Instant.parse("2000-01-01T12:00:00Z"), 70.0, 30.0));
    }
}
