package ru.agimate.controlapi.connectors.internal.astro.calc;

import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.stream.IntStream;

/** Whole Sign houses: house 1 is the ascendant's sign entire, then one sign per house. */
@UtilityClass
public class Houses {

    /** Cusps of the 12 houses: element 0 is the first house's sign (the ASC's sign). */
    public static List<ZodiacSign> wholeSignCusps(double ascendantLongitude) {
        int start = ZodiacSign.fromLongitude(ascendantLongitude).ordinal();
        return IntStream.range(0, 12)
                .mapToObj(i -> ZodiacSign.values()[(start + i) % 12])
                .toList();
    }

    /** House number (1..12) for a body, from its longitude. */
    public static int houseOf(double planetLongitude, double ascendantLongitude) {
        int planetSign = ZodiacSign.fromLongitude(planetLongitude).ordinal();
        int ascSign = ZodiacSign.fromLongitude(ascendantLongitude).ordinal();
        return 1 + (planetSign - ascSign + 12) % 12;
    }
}
