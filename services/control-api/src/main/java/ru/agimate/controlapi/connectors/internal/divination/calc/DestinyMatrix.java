package ru.agimate.controlapi.connectors.internal.divination.calc;

import lombok.experimental.UtilityClass;

import java.time.LocalDate;
import java.util.List;

/**
 * Матрица судьбы (метод Ладини): арканы 1..22 из даты рождения. Редукция {@link #r22}
 * сворачивает число только пока оно больше 22 — правило ДРУГОЕ, чем в классической
 * нумерологии ({@link Numerology}); функции не переиспользовать.
 *
 * <p>Школы расходятся в формулах производных зон — все формулы изолированы здесь.
 */
@UtilityClass
public class DestinyMatrix {

    /**
     * Точки матрицы (все — арканы 1..22).
     *
     * @param day           A, точка характера (запад)
     * @param month         B, точка талантов (север)
     * @param year          C, карма рода (восток)
     * @param mission       D, кармическая задача (юг)
     * @param center        E, зона комфорта (центр)
     * @param paternalLine  F = A+B, отцовская линия (северо-запад)
     * @param maternalLine  G = B+C, материнская линия (северо-восток)
     * @param southEast     H = C+D
     * @param southWest     I = A+D
     * @param money         линия денег
     * @param relationships линия отношений
     * @param karmicTail    кармический хвост: (I, I+D, D)
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

    /** Редукция к диапазону арканов: сворачиваем сумму цифр, только пока число больше 22. */
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
