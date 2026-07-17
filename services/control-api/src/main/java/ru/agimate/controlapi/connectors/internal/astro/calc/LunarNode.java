package ru.agimate.controlapi.connectors.internal.astro.calc;

import lombok.experimental.UtilityClass;

import java.time.Instant;

/** Средний (mean) лунный узел — полином Меёса. Движение ~0.05°/сутки, ΔT несущественна. */
@UtilityClass
public class LunarNode {

    private static final double JD_UNIX_EPOCH = 2440587.5;
    private static final double JD_J2000 = 2451545.0;
    private static final double DAYS_PER_CENTURY = 36525.0;

    /** Долгота среднего северного узла (эклиптика даты), [0, 360). */
    public static double meanNorthNodeLongitude(Instant utc) {
        double jd = utc.toEpochMilli() / 86_400_000.0 + JD_UNIX_EPOCH;
        double t = (jd - JD_J2000) / DAYS_PER_CENTURY;
        double omega = 125.0445479
                - 1934.1362891 * t
                + 0.0020754 * t * t
                + t * t * t / 467441.0
                - t * t * t * t / 60616000.0;
        return Angles.normalize(omega);
    }

    /** Южный узел — противоположная точка. */
    public static double meanSouthNodeLongitude(Instant utc) {
        return Angles.normalize(meanNorthNodeLongitude(utc) + 180.0);
    }
}
