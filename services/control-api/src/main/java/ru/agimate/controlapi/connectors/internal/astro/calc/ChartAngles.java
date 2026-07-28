package ru.agimate.controlapi.connectors.internal.astro.calc;

import io.github.cosinekitty.astronomy.Astronomy;
import io.github.cosinekitty.astronomy.Time;
import io.github.cosinekitty.astronomy.Vector;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.time.Instant;

/**
 * The chart's angles: the ascendant and the MC (Medium Coeli). Spherical trigonometry from local
 * sidereal time; the obliquity of the ecliptic is taken from the equator→ecliptic-of-date rotation
 * matrix.
 *
 * @param ascendant ecliptic longitude of the ascendant, [0, 360)
 * @param midheaven ecliptic longitude of the MC, [0, 360)
 */
public record ChartAngles(double ascendant, double midheaven) {

    /** Beyond the polar circle the ascendant degenerates (the ecliptic may not cross the horizon). */
    private static final double MAX_LATITUDE = 66.5;

    public static ChartAngles compute(Instant utc, double latitudeDeg, double longitudeEastDeg) {
        if (Math.abs(latitudeDeg) > MAX_LATITUDE) {
            throw new ConnectorException(
                    "Ascendant is undefined this close to the pole (latitude beyond ±66.5°)");
        }
        Time time = Ephemeris.toTime(utc);

        // Local sidereal time in degrees (the RAMC)
        double theta = Angles.normalize(Astronomy.siderealTime(time) * 15.0 + longitudeEastDeg);
        return fromSidereal(theta, trueObliquity(time), latitudeDeg);
    }

    /** Pure trigonometry: θ is the RAMC, ε the obliquity of the ecliptic, φ the latitude (all in degrees). */
    static ChartAngles fromSidereal(double thetaDeg, double epsDeg, double phiDeg) {
        double theta = Math.toRadians(thetaDeg);
        double eps = Math.toRadians(epsDeg);
        double phi = Math.toRadians(phiDeg);

        double mc = Math.toDegrees(Math.atan2(Math.sin(theta), Math.cos(theta) * Math.cos(eps)));
        double asc = Math.toDegrees(Math.atan2(Math.cos(theta),
                -(Math.sin(theta) * Math.cos(eps) + Math.tan(phi) * Math.sin(eps))));
        return new ChartAngles(Angles.normalize(asc), Angles.normalize(mc));
    }

    /** True obliquity of the ecliptic: the angle between the poles of the equator and of the ecliptic of date. */
    private static double trueObliquity(Time time) {
        Vector equatorPole = new Vector(0, 0, 1, time);
        Vector inEcliptic = Astronomy.rotationEqdEct(time).rotate(equatorPole);
        return Math.toDegrees(Math.acos(inEcliptic.getZ()));
    }
}
