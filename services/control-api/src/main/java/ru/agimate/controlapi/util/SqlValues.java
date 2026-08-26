package ru.agimate.controlapi.util;

import lombok.experimental.UtilityClass;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Column values of a native query. A JPQL query hands back mapped types; a native one hands back
 * whatever JDBC produced, and {@code TIMESTAMP} arrives as {@link Timestamp} — the cast that reads
 * as obviously correct, {@code (LocalDateTime) row[4]}, is the one that fails at runtime.
 */
@UtilityClass
public class SqlValues {

    /** Null-tolerant: an outer join or a {@code MAX()} over no rows legitimately yields none. */
    public static LocalDateTime localDateTime(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Timestamp timestamp ? timestamp.toLocalDateTime() : (LocalDateTime) value;
    }
}
