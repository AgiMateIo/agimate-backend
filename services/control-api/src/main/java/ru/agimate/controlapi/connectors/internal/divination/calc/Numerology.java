package ru.agimate.controlapi.connectors.internal.divination.calc;

import lombok.experimental.UtilityClass;

import java.time.LocalDate;

/**
 * Классическая нумерология по дате рождения. Редукция {@link #reduceKeepMaster} — до
 * однозначного числа с остановкой на мастер-числах 11/22/33; правило ДРУГОЕ, чем у
 * {@link DestinyMatrix#r22} (порог 22) — функции не переиспользовать.
 *
 * <p>Имённая нумерология (Expression/Destiny по ФИО) сознательно не реализована:
 * требует таблиц транслитерации, различных для RU/EN — отложено до реального запроса.
 */
@UtilityClass
public class Numerology {

    /** Число жизненного пути: день/месяц/год редуцируются по отдельности, затем сумма. */
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

    /** Число дня рождения. */
    public static int birthdayNumber(LocalDate birthDate) {
        return reduceKeepMaster(birthDate.getDayOfMonth());
    }

    /** Персональный год: день + месяц рождения + текущий год. */
    public static int personalYear(LocalDate birthDate, int currentYear) {
        return reduceKeepMaster(lifePathDay(birthDate) + lifePathMonth(birthDate)
                + reduceKeepMaster(DestinyMatrix.digitSum(currentYear)));
    }

    public static boolean isMaster(int n) {
        return n == 11 || n == 22 || n == 33;
    }

    /** Редукция к однозначному числу с остановкой на мастер-числах 11/22/33. */
    static int reduceKeepMaster(int n) {
        while (n > 9 && !isMaster(n)) {
            n = DestinyMatrix.digitSum(n);
        }
        return n;
    }
}
