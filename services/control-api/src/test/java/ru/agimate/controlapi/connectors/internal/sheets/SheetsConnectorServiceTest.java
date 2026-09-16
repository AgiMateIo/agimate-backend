package ru.agimate.controlapi.connectors.internal.sheets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.ToolAudience;
import ru.agimate.controlapi.connectors.core.dto.ToolUi;
import ru.agimate.controlapi.connectors.core.dto.ViewPage;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SheetsConnectorService")
class SheetsConnectorServiceTest {

    // The tool service is only scanned for @Tool methods here; its dependencies are never touched.
    private final SheetsConnectorService handler =
            new SheetsConnectorService(new SheetsToolService(null, null, null), null);

    @Nested
    @DisplayName("панель таблиц")
    class Panel {

        @Test
        @DisplayName("страница панели лежит в classpath и отдаётся как MCP App")
        void servesThePage() {
            ViewPage page = handler.readView(
                    new ConnectorEnv(null, null, UUID.randomUUID(), null, null, null, Map.of(), null),
                    SheetsToolService.SHEETS_VIEW);

            assertEquals("text/html;profile=mcp-app", page.mimeType());
            assertTrue(page.html().contains("ui/initialize"));
        }

        @Test
        @DisplayName("панели открыто только чтение: список и query; запись остаётся модели")
        void onlyReadsAreOpenToThePanel() {
            Map<String, ConnectorToolSpec> tools = handler.getTools();

            for (String name : List.of("list_sheets", "query")) {
                assertEquals(SheetsToolService.SHEETS_VIEW, tools.get(name).ui().resourceUri(), name);
                assertTrue(ToolUi.visibleTo(tools.get(name).ui(), ToolAudience.VIEW), name);
                assertTrue(ToolUi.visibleTo(tools.get(name).ui(), ToolAudience.MODEL), name);
            }
            for (String name : List.of("add_rows", "update_rows", "delete_rows", "delete_sheet", "create_sheet")) {
                assertFalse(ToolUi.visibleTo(tools.get(name).ui(), ToolAudience.VIEW), name);
                assertNull(tools.get(name).ui().resourceUri(), name);
            }
        }
    }
}
