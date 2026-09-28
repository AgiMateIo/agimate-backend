package ru.agimate.controlapi.connectors.internal.time;

import ru.agimate.controlapi.connectors.core.AgentTimestamps;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The time connector's settings on an agent's binding (docs/decisions/time-settings.md).
 *
 * @param timezone IANA id ({@code Europe/Moscow}); {@code null} — UTC. An id, not an offset: an offset
 *                 loses daylight saving time
 */
public record TimeSettings(String timezone) {

    public static final TimeSettings DEFAULT = new TimeSettings(null);

    /** Validated settings from user or model input; blank resets to UTC. */
    public static TimeSettings of(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return DEFAULT;
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone.strip());
        } catch (DateTimeException e) {
            throw new ConnectorException("Invalid timezone: '" + timezone + "' — expected an IANA id, e.g. Europe/Moscow");
        }
        if (zone instanceof ZoneOffset) {
            throw new ConnectorException("Invalid timezone: '" + timezone
                    + "' — an offset loses daylight saving time, pass an IANA id, e.g. Europe/Moscow");
        }
        return new TimeSettings(zone.getId());
    }

    /** The agent's zone; UTC when unset — or when a stored id has since left the tz database. */
    public ZoneId zone() {
        if (timezone == null) {
            return AgentTimestamps.UTC;
        }
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException e) {
            return AgentTimestamps.UTC;
        }
    }

    /** A stored (UTC) timestamp in the agent's zone, with the offset and the zone id. */
    public String format(LocalDateTime stored) {
        return AgentTimestamps.format(stored, zone());
    }
}
