package ru.agimate.controlapi.connectors.internal.astro.calc;

import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.stream.IntStream;

/** Дома Whole Sign: дом 1 — целиком знак асцендента, дальше по знаку на дом. */
@UtilityClass
public class Houses {

    /** Куспиды 12 домов: элемент 0 — знак первого дома (знак ASC). */
    public static List<ZodiacSign> wholeSignCusps(double ascendantLongitude) {
        int start = ZodiacSign.fromLongitude(ascendantLongitude).ordinal();
        return IntStream.range(0, 12)
                .mapToObj(i -> ZodiacSign.values()[(start + i) % 12])
                .toList();
    }

    /** Номер дома (1..12) для тела по его долготе. */
    public static int houseOf(double planetLongitude, double ascendantLongitude) {
        int planetSign = ZodiacSign.fromLongitude(planetLongitude).ordinal();
        int ascSign = ZodiacSign.fromLongitude(ascendantLongitude).ordinal();
        return 1 + (planetSign - ascSign + 12) % 12;
    }
}
