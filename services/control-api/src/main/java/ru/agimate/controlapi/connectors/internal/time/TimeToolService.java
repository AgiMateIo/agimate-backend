package ru.agimate.controlapi.connectors.internal.time;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Service;

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

    @Tool(name = "time.current_datetime", value = "Get the current date and time in UTC (ISO-8601)")
    public Map<String, Object> currentDateTime() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        return Map.of(
                "dateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "zone", "UTC");
    }
}
