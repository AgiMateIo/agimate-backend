package ru.agimate.controlapi.connectors.internal.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorSettingsService;
import ru.agimate.controlapi.connectors.core.dto.PromptBlock;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("TimeConnectorService")
class TimeConnectorServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();

    private final AgentConnectionRepository agentConnectionRepository = mock(AgentConnectionRepository.class);
    private final ConnectorSettingsService settingsService = new ConnectorSettingsService(agentConnectionRepository);
    // taskService/triggerRouter не нужны для метаданных и current_datetime — передаём null.
    private final TimeConnectorService handler = new TimeConnectorService(
            new TimeToolService(null, null, null, settingsService, null), settingsService);

    private static ConnectorEnv env() {
        return new ConnectorEnv(null, null, null, null, null, null, Map.of(), null);
    }

    @Test
    @DisplayName("метаданные: LLM-тулы, скрытая таска fire, триггер due")
    void metadata() {
        assertEquals("time", handler.connectorCode());
        assertEquals("Time", handler.connectorName());

        Map<String, ConnectorToolSpec> tools = handler.getTools();
        assertEquals(7, tools.size());
        assertNotNull(tools.get("current_datetime"));
        assertTrue(tools.get("current_datetime").annotations().readOnlyHint());
        assertNotNull(tools.get("schedule"));
        assertNotNull(tools.get("scheduled_tasks"));
        assertNotNull(tools.get("cancel_scheduled"));
        assertNotNull(tools.get("set_timezone"));
        assertNotNull(tools.get("get_settings"));
        assertNotNull(tools.get("save_settings"));
        // fire — @Tool(visibility = {}): скрыта от LLM, но это цель динамического диспатча, НЕ
        // декларативная джоба, иначе reconcile завёл бы фоновую SYSTEM-строку без агента-инициатора.
        assertNull(tools.get("fire"));
        assertTrue(handler.getJobs().isEmpty());

        assertEquals(1, handler.getTriggers().size());
        assertNotNull(handler.getTriggers().get("due"));
    }

    @Test
    @DisplayName("current_datetime без пояса агента возвращает UTC с именем зоны")
    void currentDateTime() {
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5);

        Map<String, Object> result = handler.executeTool(env(), "current_datetime", Map.of());

        assertEquals("UTC", result.get("zone"));
        String dateTime = (String) result.get("dateTime");
        assertTrue(dateTime.endsWith("Z[UTC]"));
        OffsetDateTime parsed = ZonedDateTime.parse(dateTime, DateTimeFormatter.ISO_ZONED_DATE_TIME).toOffsetDateTime();
        assertEquals(ZoneOffset.UTC, parsed.getOffset());
        assertTrue(parsed.isAfter(before));
        assertTrue(parsed.isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(5)));
    }

    @Nested
    @DisplayName("блок timezone")
    class TimezoneBlock {

        private ConnectorEnv agentEnv() {
            return new ConnectorEnv(CONNECTION_ID.toString(), null, AGENT_ID, null, null, null, Map.of(), null);
        }

        @Test
        @DisplayName("пояс задан — стабильный системный блок с его именем")
        void zoneSet() {
            when(agentConnectionRepository.findActiveBinding(AGENT_ID, CONNECTION_ID)).thenReturn(Optional.of(
                    AgentConnection.builder().settings(new HashMap<>(Map.of("timezone", "Europe/Moscow"))).build()));

            List<PromptBlock> blocks = handler.promptBlocks(agentEnv());

            assertEquals(1, blocks.size());
            assertEquals(TimeConnectorService.TIMEZONE_BLOCK, blocks.getFirst().name());
            assertEquals(PromptBlock.Placement.SYSTEM, blocks.getFirst().placement());
            assertTrue(blocks.getFirst().stable());
            assertTrue(blocks.getFirst().content().contains("Europe/Moscow"));
        }

        @Test
        @DisplayName("пояс не задан — блок говорит про UTC и set_timezone")
        void zoneUnset() {
            List<PromptBlock> blocks = handler.promptBlocks(agentEnv());

            assertTrue(blocks.getFirst().content().contains("UTC"));
            assertTrue(blocks.getFirst().content().contains("set_timezone"));
        }

        @Test
        @DisplayName("вне агента блока нет")
        void noAgent() {
            assertTrue(handler.promptBlocks(env()).isEmpty());
        }
    }

    @Test
    @DisplayName("панель настроек отдаётся с classpath")
    void settingsView() {
        assertTrue(handler.readView(env(), TimeToolService.SETTINGS_VIEW).html().contains("save_settings"));
    }
}
