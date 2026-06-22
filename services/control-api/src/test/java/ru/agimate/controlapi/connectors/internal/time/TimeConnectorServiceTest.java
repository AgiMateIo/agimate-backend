package ru.agimate.controlapi.connectors.internal.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.ConnectorContext;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TimeConnectorService")
class TimeConnectorServiceTest {

    // taskService/triggerRouter/channelSessionRepository не нужны для метаданных и
    // current_datetime — передаём null.
    private final TimeConnectorService handler =
            new TimeConnectorService(new TimeToolService(null, null, null));

    private static ConnectorContext context() {
        return new ConnectorContext(null, null, null, null, Map.of(), null);
    }

    @Test
    @DisplayName("метаданные: LLM-тулы, скрытая таска fire, триггер due")
    void metadata() {
        assertEquals("time", handler.connectorCode());
        assertEquals("Time", handler.connectorName());

        Map<String, ConnectorToolSpec> tools = handler.getTools();
        assertEquals(4, tools.size());
        assertNotNull(tools.get("time.current_datetime"));
        assertTrue(tools.get("time.current_datetime").annotations().readOnlyHint());
        assertNotNull(tools.get("time.schedule"));
        assertNotNull(tools.get("time.scheduled_tasks"));
        assertNotNull(tools.get("time.cancel_scheduled"));
        // fire — task-only, скрыта от LLM, но регистрируется как таска-диспетчер.
        assertNull(tools.get("time.fire"));

        assertEquals(1, handler.getJobs().size());
        assertNotNull(handler.getJobs().get("time.fire"));

        assertEquals(1, handler.getTriggers().size());
        assertNotNull(handler.getTriggers().get("trigger.time.due"));
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
