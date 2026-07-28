package ru.agimate.controlapi.connectors.internal.astro.calc;

/** Signs of the tropical zodiac, 30° of ecliptic longitude each, starting at 0° Aries. */
public enum ZodiacSign {
    ARIES("Aries"),
    TAURUS("Taurus"),
    GEMINI("Gemini"),
    CANCER("Cancer"),
    LEO("Leo"),
    VIRGO("Virgo"),
    LIBRA("Libra"),
    SCORPIO("Scorpio"),
    SAGITTARIUS("Sagittarius"),
    CAPRICORN("Capricorn"),
    AQUARIUS("Aquarius"),
    PISCES("Pisces");

    private final String title;

    ZodiacSign(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    /** The sign for an ecliptic longitude (any value is normalised to [0, 360)). */
    public static ZodiacSign fromLongitude(double longitude) {
        return values()[(int) (Angles.normalize(longitude) / 30.0)];
    }

    /** Degree within the sign: [0, 30). */
    public static double degreeInSign(double longitude) {
        return Angles.normalize(longitude) % 30.0;
    }
}
