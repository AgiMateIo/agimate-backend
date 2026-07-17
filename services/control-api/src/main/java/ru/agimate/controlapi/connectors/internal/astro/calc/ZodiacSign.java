package ru.agimate.controlapi.connectors.internal.astro.calc;

/** Знаки тропического зодиака по 30° эклиптической долготы, начиная с 0° Овна. */
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

    /** Знак по эклиптической долготе (любое значение нормализуется к [0, 360)). */
    public static ZodiacSign fromLongitude(double longitude) {
        return values()[(int) (Angles.normalize(longitude) / 30.0)];
    }

    /** Градус внутри знака: [0, 30). */
    public static double degreeInSign(double longitude) {
        return Angles.normalize(longitude) % 30.0;
    }
}
