package ru.agimate.controlapi.database.enums;

import java.time.LocalDate;

/** Calendar window of a counter or quota (UTC): DAY — a day, MONTH — a month (window_start = the 1st). */
public enum UsageWindow {
    DAY,
    MONTH;

    /** Start of the window {@code date} falls into. */
    public LocalDate windowStart(LocalDate date) {
        return this == DAY ? date : date.withDayOfMonth(1);
    }
}
