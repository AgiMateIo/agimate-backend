package ru.agimate.controlapi.connectors.internal.time;

import org.springframework.stereotype.Service;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Тулы time-коннектора.
 */
@Service
public class TimeToolService {

    @Tool(name = "time.current_datetime", description = "Get the current date and time in UTC (ISO-8601)",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> currentDateTime() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        return Map.of(
                "dateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "zone", "UTC");
    }
}
