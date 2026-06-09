package ru.agimate.controlapi.connectors.internal.time;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.ConnectorContext;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TimeConnectorService")
class TimeConnectorServiceTest {

    private final TimeConnectorService handler = new TimeConnectorService(new TimeToolService());

    private static ConnectorContext context() {
        return new ConnectorContext(null, null, null, Map.of(), null);
    }

    @Test
    @DisplayName("метаданные: code/name, одна тула, без тасок и триггеров")
    void metadata() {
        assertEquals("time", handler.connectorCode());
        assertEquals("Time", handler.connectorName());

        Map<String, ToolSpecification> tools = handler.getTools();
        assertEquals(1, tools.size());
        assertNotNull(tools.get("time.current_datetime"));

        assertTrue(handler.getTasks().isEmpty());
        assertTrue(handler.getTriggers().isEmpty());
    }

    @Test
    @DisplayName("current_datetime возвращает текущие дату и время в UTC (ISO-8601)")
    void currentDateTime() {
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5);

        Map<String, Object> result = handler.executeTool(context(), "time.current_datetime", Map.of());

        assertEquals("UTC", result.get("zone"));
        OffsetDateTime parsed = OffsetDateTime.parse(
                (String) result.get("dateTime"), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        assertEquals(ZoneOffset.UTC, parsed.getOffset());
        assertTrue(parsed.isAfter(before));
        assertTrue(parsed.isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(5)));
    }
}
