package ru.agimate.controlapi.connectors.internal.astro.calc;

import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * A parsed moment of birth. When the time is unknown, noon is used (in the tzid, or UTC) and
 * {@code timeKnown=false}: the positions of the slow bodies stay valid, the Moon is an estimate to
 * ±6.5°, and angles and houses are unavailable.
 *
 * @param utc       the moment in UTC
 * @param timeKnown whether the exact time of birth is known
 */
public record BirthMoment(Instant utc, boolean timeKnown) {

    public static BirthMoment resolve(String birthDate, String birthTime, String tzid) {
        LocalDate date = parseDate(birthDate);
        if (birthTime == null || birthTime.isBlank()) {
            ZoneId zone = tzid == null || tzid.isBlank() ? ZoneOffset.UTC : parseZone(tzid);
            return new BirthMoment(date.atTime(LocalTime.NOON).atZone(zone).toInstant(), false);
        }
        if (tzid == null || tzid.isBlank()) {
            throw new ConnectorException("tzid is required when birthTime is provided");
        }
        LocalTime time;
        try {
            time = LocalTime.parse(birthTime);
        } catch (DateTimeParseException e) {
            throw new ConnectorException("Invalid birthTime '" + birthTime + "': expected HH:mm");
        }
        // Historical offsets (wartime time and the like) are resolved by the JDK's tzdb
        return new BirthMoment(date.atTime(time).atZone(parseZone(tzid)).toInstant(), true);
    }

    static LocalDate parseDate(String birthDate) {
        try {
            return LocalDate.parse(birthDate);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ConnectorException("Invalid birthDate '" + birthDate + "': expected YYYY-MM-DD");
        }
    }

    private static ZoneId parseZone(String tzid) {
        try {
            return ZoneId.of(tzid);
        } catch (DateTimeException e) {
            throw new ConnectorException("Unknown IANA timezone '" + tzid + "'");
        }
    }
}
