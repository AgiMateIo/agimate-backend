package ru.agimate.controlapi.connectors.internal.divination.calc;

import lombok.experimental.UtilityClass;

import java.time.LocalDate;
import java.util.List;

/**
 * The Destiny Matrix (Ladini's method): arcana 1..22 derived from a date of birth. The reduction
 * {@link #r22} folds a number only while it exceeds 22 — a DIFFERENT rule from classical numerology
 * ({@link Numerology}); do not reuse the functions across the two.
 *
 * <p>Schools disagree on the formulas for the derived zones — every formula is isolated here.
 */
@UtilityClass
public class DestinyMatrix {

    /**
     * Points of the matrix (all of them arcana 1..22).
     *
     * @param day           A, the point of character (west)
     * @param month         B, the point of talents (north)
     * @param year          C, the family's karma (east)
     * @param mission       D, the karmic task (south)
     * @param center        E, the comfort zone (centre)
     * @param paternalLine  F = A+B, the paternal line (north-west)
     * @param maternalLine  G = B+C, the maternal line (north-east)
     * @param southEast     H = C+D
     * @param southWest     I = A+D
     * @param money         the money line
     * @param relationships the relationship line
     * @param karmicTail    the karmic tail: (I, I+D, D)
     */
    public record MatrixResult(int day, int month, int year, int mission, int center,
                               int paternalLine, int maternalLine, int southEast, int southWest,
                               int money, int relationships, List<Integer> karmicTail) {}

    public static MatrixResult compute(LocalDate birthDate) {
        int a = r22(birthDate.getDayOfMonth());
        int b = r22(birthDate.getMonthValue());
        int c = r22(digitSum(birthDate.getYear()));
        int d = r22(a + b + c);
        int e = r22(a + b + c + d);

        int f = r22(a + b);
        int g = r22(b + c);
        int h = r22(c + d);
        int i = r22(a + d);

        int relationships = r22(d + h);
        int money = r22(c + h);
        List<Integer> karmicTail = List.of(i, r22(i + d), d);

        return new MatrixResult(a, b, c, d, e, f, g, h, i, money, relationships, karmicTail);
    }

    /** Reduction into the arcana range: fold the digit sum only while the number exceeds 22. */
    static int r22(int n) {
        while (n > 22) {
            n = digitSum(n);
        }
        return n;
    }

    static int digitSum(int n) {
        int sum = 0;
        for (; n > 0; n /= 10) {
            sum += n % 10;
        }
        return sum;
    }
}
