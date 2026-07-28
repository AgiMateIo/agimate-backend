package ru.agimate.controlapi.connectors.internal.astro.calc;

/**
 * Geocentric ecliptic position of a body (ecliptic of date, tropical zodiac).
 *
 * @param body       the body's name ("Sun".."Pluto")
 * @param longitude  ecliptic longitude, [0, 360)
 * @param latitude   ecliptic latitude, in degrees
 * @param retrograde whether the apparent motion is retrograde (always false for the Sun and Moon)
 */
public record PlanetPosition(String body, double longitude, double latitude, boolean retrograde) {

    public ZodiacSign sign() {
        return ZodiacSign.fromLongitude(longitude);
    }
}
