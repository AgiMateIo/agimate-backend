package ru.agimate.controlapi.connectors.internal.astro.calc;

import io.github.cosinekitty.astronomy.Astronomy;
import io.github.cosinekitty.astronomy.Time;
import io.github.cosinekitty.astronomy.Vector;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.time.Instant;

/**
 * Углы карты: асцендент и MC (Medium Coeli). Сферическая тригонометрия от местного
 * звёздного времени; наклон эклиптики берётся из матрицы поворота экватор→эклиптика даты.
 *
 * @param ascendant эклиптическая долгота асцендента, [0, 360)
 * @param midheaven эклиптическая долгота MC, [0, 360)
 */
public record ChartAngles(double ascendant, double midheaven) {

    /** За полярным кругом асцендент вырождается (эклиптика может не пересекать горизонт). */
    private static final double MAX_LATITUDE = 66.5;

    public static ChartAngles compute(Instant utc, double latitudeDeg, double longitudeEastDeg) {
        if (Math.abs(latitudeDeg) > MAX_LATITUDE) {
            throw new ConnectorException(
                    "Ascendant is undefined this close to the pole (latitude beyond ±66.5°)");
        }
        Time time = Ephemeris.toTime(utc);

        // Местное звёздное время в градусах (RAMC)
        double theta = Angles.normalize(Astronomy.siderealTime(time) * 15.0 + longitudeEastDeg);
        return fromSidereal(theta, trueObliquity(time), latitudeDeg);
    }

    /** Чистая тригонометрия: θ — RAMC, ε — наклон эклиптики, φ — широта (всё в градусах). */
    static ChartAngles fromSidereal(double thetaDeg, double epsDeg, double phiDeg) {
        double theta = Math.toRadians(thetaDeg);
        double eps = Math.toRadians(epsDeg);
        double phi = Math.toRadians(phiDeg);

        double mc = Math.toDegrees(Math.atan2(Math.sin(theta), Math.cos(theta) * Math.cos(eps)));
        double asc = Math.toDegrees(Math.atan2(Math.cos(theta),
                -(Math.sin(theta) * Math.cos(eps) + Math.tan(phi) * Math.sin(eps))));
        return new ChartAngles(Angles.normalize(asc), Angles.normalize(mc));
    }

    /** Истинный наклон эклиптики: угол между полюсами экватора и эклиптики даты. */
    private static double trueObliquity(Time time) {
        Vector equatorPole = new Vector(0, 0, 1, time);
        Vector inEcliptic = Astronomy.rotationEqdEct(time).rotate(equatorPole);
        return Math.toDegrees(Math.acos(inEcliptic.getZ()));
    }
}
