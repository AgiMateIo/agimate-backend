package ru.agimate.controlapi.connectors.internal.astro.calc;

import io.github.cosinekitty.astronomy.Aberration;
import io.github.cosinekitty.astronomy.Astronomy;
import io.github.cosinekitty.astronomy.Body;
import io.github.cosinekitty.astronomy.Ecliptic;
import io.github.cosinekitty.astronomy.Spherical;
import io.github.cosinekitty.astronomy.Time;
import lombok.experimental.UtilityClass;

import java.time.Instant;
import java.util.List;

/**
 * A wrapper over Astronomy Engine: geocentric positions of bodies in the true ecliptic of date (the
 * tropical zodiac). The engine is accurate to ±1 arc minute — more than enough for astrology.
 */
@UtilityClass
public class Ephemeris {

    /** Bodies of a natal chart, in the canonical order. */
    static final List<Body> BODIES = List.of(
            Body.Sun, Body.Moon, Body.Mercury, Body.Venus, Body.Mars,
            Body.Jupiter, Body.Saturn, Body.Uranus, Body.Neptune, Body.Pluto);

    /** Finite-difference step for determining retrogradation, in days. */
    private static final double RETROGRADE_STEP_DAYS = 0.5;

    /** Positions of all 10 bodies at a moment in time. */
    public static List<PlanetPosition> positions(Instant utc) {
        Time time = toTime(utc);
        return BODIES.stream().map(body -> position(body, time)).toList();
    }

    static PlanetPosition position(Body body, Time time) {
        double longitude = eclipticLongitude(body, time);
        double latitude = eclipticLatitude(body, time);
        boolean retrograde = body != Body.Sun && body != Body.Moon && isRetrograde(body, time);
        return new PlanetPosition(body.name(), longitude, latitude, retrograde);
    }

    /** Ecliptic longitude of a body (ecliptic of date), [0, 360). */
    public static double eclipticLongitude(Body body, Time time) {
        if (body == Body.Sun) {
            return Angles.normalize(Astronomy.sunPosition(time).getElon());
        }
        if (body == Body.Moon) {
            return Angles.normalize(Astronomy.eclipticGeoMoon(time).getLon());
        }
        return Angles.normalize(ecliptic(body, time).getElon());
    }

    private static double eclipticLatitude(Body body, Time time) {
        if (body == Body.Sun) {
            return Astronomy.sunPosition(time).getElat();
        }
        if (body == Body.Moon) {
            Spherical moon = Astronomy.eclipticGeoMoon(time);
            return moon.getLat();
        }
        return ecliptic(body, time).getElat();
    }

    private static Ecliptic ecliptic(Body body, Time time) {
        return Astronomy.equatorialToEcliptic(Astronomy.geoVector(body, time, Aberration.Corrected));
    }

    /** Apparent retrograde motion: the longitude decreases over the ±12-hour interval around the moment. */
    static boolean isRetrograde(Body body, Time time) {
        double before = eclipticLongitude(body, time.addDays(-RETROGRADE_STEP_DAYS));
        double after = eclipticLongitude(body, time.addDays(RETROGRADE_STEP_DAYS));
        double delta = after - before;
        // Crossing 0°/360°: bring the difference into (-180, 180]
        if (delta > 180) {
            delta -= 360;
        } else if (delta < -180) {
            delta += 360;
        }
        return delta < 0;
    }

    static Time toTime(Instant utc) {
        return Time.fromMillisecondsSince1970(utc.toEpochMilli());
    }
}
