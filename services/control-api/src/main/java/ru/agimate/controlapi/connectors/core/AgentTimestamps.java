package ru.agimate.controlapi.connectors.core;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import lombok.experimental.UtilityClass;
import ru.agimate.common.util.JsonUtils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Timestamps as an agent sees them: one {@code ISO_ZONED_DATE_TIME} string carrying both the offset
 * and the zone id ({@code 2026-09-28T17:40:00+03:00[Europe/Moscow]}, {@code 2026-09-28T14:40:00Z[UTC]}).
 * A stored {@link LocalDateTime} is UTC (the JVM is pinned to it), so every conversion starts there.
 */
@UtilityClass
public class AgentTimestamps {

    public static final ZoneId UTC = ZoneId.of("UTC");

    private static final ObjectMapper UTC_MAPPER = JsonUtils.MAPPER.copy()
            .registerModule(new SimpleModule().addSerializer(LocalDateTime.class, new JsonSerializer<>() {
                @Override
                public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                        throws IOException {
                    gen.writeString(utc(value));
                }
            }));

    public static String format(LocalDateTime stored, ZoneId zone) {
        return stored == null ? null : stored.truncatedTo(ChronoUnit.SECONDS)
                .atZone(ZoneOffset.UTC)
                .withZoneSameInstant(zone)
                .format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }

    public static String utc(LocalDateTime stored) {
        return format(stored, UTC);
    }

    /**
     * A DTO shared with HTTP, as a map for an agent: its {@link LocalDateTime} fields become UTC strings
     * with the zone, while the HTTP contract of the same record stays as it is.
     */
    public static Map<String, Object> toMap(Object value) {
        return UTC_MAPPER.convertValue(value, JsonUtils.MAP_TYPE_REFERENCE);
    }

    /**
     * An incoming timestamp as stored (UTC): an offset or a zone is honoured, a bare local date-time is
     * read as UTC. {@code null} for blank.
     *
     * @throws ConnectorException when the value is none of those
     */
    public static LocalDateTime parseUtc(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        try {
            return ZonedDateTime.parse(v, DateTimeFormatter.ISO_DATE_TIME)
                    .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // no offset or zone — a local date-time, taken as UTC below
        }
        try {
            return LocalDateTime.parse(v);
        } catch (DateTimeParseException e) {
            throw new ConnectorException("Invalid " + field + ": '" + value + "' — expected an ISO date-time, "
                    + "e.g. 2026-09-01T10:00:00Z or 2026-09-01T13:00:00+03:00 (without an offset it is UTC)");
        }
    }
}
