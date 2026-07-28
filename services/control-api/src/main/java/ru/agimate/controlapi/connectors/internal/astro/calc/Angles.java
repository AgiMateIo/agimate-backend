package ru.agimate.controlapi.connectors.internal.astro.calc;

import lombok.experimental.UtilityClass;

/** Common operations on angles in degrees. */
@UtilityClass
public class Angles {

    /** Normalisation to [0, 360). */
    public static double normalize(double degrees) {
        double d = degrees % 360.0;
        return d < 0 ? d + 360.0 : d;
    }

    /** Angular distance between two longitudes: [0, 180]. */
    public static double separation(double lon1, double lon2) {
        return 180.0 - Math.abs(180.0 - normalize(lon1 - lon2));
    }

    /** The «21°14' Leo» format for an ecliptic longitude. */
    public static String formatZodiac(double longitude) {
        double inSign = ZodiacSign.degreeInSign(longitude);
        int deg = (int) inSign;
        int min = (int) Math.round((inSign - deg) * 60.0);
        if (min == 60) { // округление 59.5+' наверх не должно давать «22°60'»
            deg++;
            min = 0;
        }
        return "%d°%02d' %s".formatted(deg, min, ZodiacSign.fromLongitude(longitude).title());
    }
}
