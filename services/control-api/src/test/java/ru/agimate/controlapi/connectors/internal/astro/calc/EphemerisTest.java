package ru.agimate.controlapi.connectors.internal.astro.calc;

import io.github.cosinekitty.astronomy.Astronomy;
import io.github.cosinekitty.astronomy.Body;
import io.github.cosinekitty.astronomy.Time;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Ephemeris: сверка с эталоном JPL Horizons")
class EphemerisTest {

    /** 2000-01-01T12:00Z — эклиптика даты ≈ J2000, эталоны Horizons однозначны. */
    private static final Instant EPOCH = Instant.parse("2000-01-01T12:00:00Z");

    @Nested
    @DisplayName("Эклиптические долготы (геоцентрические, apparent)")
    class Longitudes {

        @Test
        @DisplayName("Солнце: 280.3689° (10°22' Козерога)")
        void sun() {
            double lon = Ephemeris.eclipticLongitude(Body.Sun, Ephemeris.toTime(EPOCH));
            assertEquals(280.3689, lon, 0.02);
            assertEquals(ZodiacSign.CAPRICORN, ZodiacSign.fromLongitude(lon));
        }

        @Test
        @DisplayName("Луна: 223.3238° (13°19' Скорпиона), широта 5.1707°")
        void moon() {
            List<PlanetPosition> positions = Ephemeris.positions(EPOCH);
            PlanetPosition moon = positions.get(1);
            assertEquals("Moon", moon.body());
            assertEquals(223.3238, moon.longitude(), 0.05);
            assertEquals(5.1707, moon.latitude(), 0.05);
            assertEquals(ZodiacSign.SCORPIO, moon.sign());
        }

        @Test
        @DisplayName("Марс: 327.9633° (27°58' Водолея)")
        void mars() {
            double lon = Ephemeris.eclipticLongitude(Body.Mars, Ephemeris.toTime(EPOCH));
            assertEquals(327.9633, lon, 0.05);
        }

        @Test
        @DisplayName("Self-consistency: долгота Солнца в мартовское равноденствие ≈ 0°")
        void marchEquinox() {
            Time equinox = Astronomy.seasons(2026).getMarchEquinox();
            double lon = Ephemeris.eclipticLongitude(Body.Sun, equinox);
            double distanceFromZero = Math.min(lon, 360.0 - lon);
            assertTrue(distanceFromZero < 1.0 / 60.0,
                    "Sun longitude at equinox should be ~0°, was " + lon);
        }
    }

    @Nested
    @DisplayName("Ретроградность")
    class Retrograde {

        @Test
        @DisplayName("Марс ретрограден 2003-08-28 (известная петля июль–сентябрь 2003)")
        void marsRetrograde() {
            assertTrue(Ephemeris.isRetrograde(Body.Mars,
                    Ephemeris.toTime(Instant.parse("2003-08-28T00:00:00Z"))));
        }

        @Test
        @DisplayName("Марс директен 2003-01-15")
        void marsDirect() {
            assertFalse(Ephemeris.isRetrograde(Body.Mars,
                    Ephemeris.toTime(Instant.parse("2003-01-15T00:00:00Z"))));
        }

        @Test
        @DisplayName("Солнце и Луна никогда не помечаются ретроградными")
        void sunAndMoonNeverRetrograde() {
            for (PlanetPosition p : Ephemeris.positions(EPOCH)) {
                if (p.body().equals("Sun") || p.body().equals("Moon")) {
                    assertFalse(p.retrograde(), p.body());
                }
            }
        }
    }

    @Test
    @DisplayName("positions: 10 тел в каноническом порядке")
    void allBodies() {
        List<PlanetPosition> positions = Ephemeris.positions(EPOCH);
        assertEquals(10, positions.size());
        assertEquals("Sun", positions.getFirst().body());
        assertEquals("Pluto", positions.getLast().body());
    }
}
