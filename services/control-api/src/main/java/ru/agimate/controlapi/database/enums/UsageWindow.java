package ru.agimate.controlapi.database.enums;

import java.time.LocalDate;

/** Календарное окно счётчика/квоты (UTC): DAY — день, MONTH — месяц (window_start = 1-е число). */
public enum UsageWindow {
    DAY,
    MONTH;

    /** Начало окна, в которое попадает {@code date}. */
    public LocalDate windowStart(LocalDate date) {
        return this == DAY ? date : date.withDayOfMonth(1);
    }
}
