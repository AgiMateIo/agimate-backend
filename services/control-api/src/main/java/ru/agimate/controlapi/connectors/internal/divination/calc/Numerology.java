package ru.agimate.controlapi.connectors.internal.divination.calc;

import lombok.experimental.UtilityClass;

import java.time.LocalDate;

/**
 * Classical numerology from a date of birth. The reduction {@link #reduceKeepMaster} goes down to a
 * single digit but stops at the master numbers 11/22/33 — a DIFFERENT rule from
 * {@link DestinyMatrix#r22} (which uses a threshold of 22); do not reuse the functions across the two.
 *
 * <p>Name numerology (Expression/Destiny from a full name) is deliberately not implemented: it needs
 * transliteration tables that differ between RU and EN — deferred until there is a real request.
 */
@UtilityClass
public class Numerology {

    /** The life path number: day, month and year are reduced separately, then summed. */
    public static int lifePath(LocalDate birthDate) {
        return reduceKeepMaster(lifePathDay(birthDate) + lifePathMonth(birthDate) + lifePathYear(birthDate));
    }

    public static int lifePathDay(LocalDate birthDate) {
        return reduceKeepMaster(birthDate.getDayOfMonth());
    }

    public static int lifePathMonth(LocalDate birthDate) {
        return reduceKeepMaster(birthDate.getMonthValue());
    }

    public static int lifePathYear(LocalDate birthDate) {
        return reduceKeepMaster(DestinyMatrix.digitSum(birthDate.getYear()));
    }

    /** The birthday number. */
    public static int birthdayNumber(LocalDate birthDate) {
        return reduceKeepMaster(birthDate.getDayOfMonth());
    }

    /** The personal year: day + month of birth + the current year. */
    public static int personalYear(LocalDate birthDate, int currentYear) {
        return reduceKeepMaster(lifePathDay(birthDate) + lifePathMonth(birthDate)
                + reduceKeepMaster(DestinyMatrix.digitSum(currentYear)));
    }

    public static boolean isMaster(int n) {
        return n == 11 || n == 22 || n == 33;
    }

    /** Reduction to a single digit, stopping at the master numbers 11/22/33. */
    static int reduceKeepMaster(int n) {
        while (n > 9 && !isMaster(n)) {
            n = DestinyMatrix.digitSum(n);
        }
        return n;
    }
}
