package ru.agimate.controlapi.connectors.internal.astro.calc;

/** Мажорные аспекты. */
public enum AspectType {
    CONJUNCTION(0),
    SEXTILE(60),
    SQUARE(90),
    TRINE(120),
    OPPOSITION(180);

    private final int angle;

    AspectType(int angle) {
        this.angle = angle;
    }

    public int angle() {
        return angle;
    }

    public String title() {
        return name().toLowerCase();
    }
}
