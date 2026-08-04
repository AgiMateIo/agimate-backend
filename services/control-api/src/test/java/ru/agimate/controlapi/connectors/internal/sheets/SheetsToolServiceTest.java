package ru.agimate.controlapi.connectors.internal.sheets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.AggregateResult;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.ChartResult;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.ColumnSpec;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.ColumnSummary;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.Condition;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.FileInfo;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.GroupResult;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.Metric;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.SheetDetail;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("sheets-коннектор — тулы через executeTool (env биндится по-настоящему)")
class SheetsToolServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final ConnectorEnv env = new ConnectorEnv(
            null, userId, agentId, UUID.randomUUID(), null, null, Map.of(), null);

    @Mock
    private SheetsService sheetsService;
    @Mock
    private SheetChartService chartService;
    @Mock
    private SheetFileService fileService;

    private SheetsConnectorService handler;

    @BeforeEach
    void setUp() {
        handler = new SheetsConnectorService(
                new SheetsToolService(sheetsService, chartService, fileService), sheetsService);
    }

    @Test
    @DisplayName("create_sheet: объявление колонок доезжает типизированным, ответ — плоская Map")
    void createSheetBindsColumnSpecs() {
        List<ColumnSpec> columns = List.of(
                new ColumnSpec("date", "Дата", "date", ""),
                new ColumnSpec("amount", "Сумма", "number", "₽"));
        when(sheetsService.createSheet(eq(agentId), eq(userId), eq("budget"), eq("Бюджет"), any()))
                .thenReturn(new SheetDetail("budget", "Бюджет", columns, 0L));

        Map<String, Object> result = handler.executeTool(env, "create_sheet", Map.of(
                "name", "budget",
                "title", "Бюджет",
                "columns", List.of(
                        Map.of("name", "date", "title", "Дата", "type", "date", "unit", ""),
                        Map.of("name", "amount", "title", "Сумма", "type", "number", "unit", "₽"))));

        assertEquals("budget", result.get("name"));
        assertEquals("Бюджет", result.get("title"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ColumnSpec>> captor = ArgumentCaptor.forClass(List.class);
        verify(sheetsService).createSheet(eq(agentId), eq(userId), eq("budget"), eq("Бюджет"),
                captor.capture());
        assertEquals(columns, captor.getValue());
    }

    @Test
    @DisplayName("aggregate: метрики и фильтр приходят структурой, а не строкой SQL")
    void aggregateBindsMetricsAndFilter() {
        when(sheetsService.aggregate(eq(agentId), eq("budget"), eq("category"), isNull(), any(), any()))
                .thenReturn(new AggregateResult("budget", "category",
                        List.of(new GroupResult("продукты", Map.of("sum_amount", 12_400d)))));

        Map<String, Object> result = handler.executeTool(env, "aggregate", Map.of(
                "sheet", "budget",
                "groupBy", "category",
                "metrics", List.of(Map.of("func", "sum", "column", "amount")),
                "filter", List.of(Map.of("column", "amount", "op", "gte", "value", "100"))));

        assertEquals("category", result.get("groupBy"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Metric>> metrics = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Condition>> filter = ArgumentCaptor.forClass(List.class);
        verify(sheetsService).aggregate(eq(agentId), eq("budget"), eq("category"), isNull(),
                metrics.capture(), filter.capture());
        assertEquals(new Metric("amount", "sum"), metrics.getValue().getFirst());
        assertEquals("gte", filter.getValue().getFirst().op());
    }

    @Test
    @DisplayName("render_chart: вместе с файлом возвращается сводка — агент не видит собственный PNG")
    void chartReturnsSummaryAlongsideFile() {
        when(chartService.render(eq(agentId), eq(userId), eq(agentId), eq("pressure"), isNull(),
                eq("date"), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new ChartResult(
                        new FileInfo("agf_" + UUID.randomUUID(), "image/png", 42_000L), "pressure",
                        List.of(new ColumnSummary("sys", "мм рт.ст.", 30, 118d, 152d, 131.4d, 3942d))));

        Map<String, Object> result = handler.executeTool(env, "render_chart", Map.of(
                "sheet", "pressure", "x", "date", "y", List.of("sys")));

        assertNotNull(result.get("file"));
        assertNotNull(result.get("summary"));
    }

    @Test
    @DisplayName("тулы требуют контекста агента: пространство листов личное")
    void requiresAgentContext() {
        ConnectorEnv headless = new ConnectorEnv(null, userId, null, null, null, null, Map.of(), null);
        ConnectorException error = assertThrows(ConnectorException.class,
                () -> handler.executeTool(headless, "list_sheets", Map.of()));
        assertTrue(error.getMessage().contains("agent context"), error.getMessage());
    }

    @Test
    @DisplayName("export: неизвестный формат отвергается со списком допустимых")
    void rejectsUnknownExportFormat() {
        when(sheetsService.requireSheet(eq(agentId), anyString()))
                .thenThrow(new ConnectorException("No sheet 'budget'. Existing sheets: (none yet)"));

        ConnectorException error = assertThrows(ConnectorException.class,
                () -> handler.executeTool(env, "export", Map.of("sheet", "budget", "format", "pdf")));
        assertTrue(error.getMessage().contains("budget"), error.getMessage());
    }

    @Test
    @DisplayName("неизвестный тул не диспатчится")
    void unknownToolRejected() {
        assertThrows(ConnectorException.class,
                () -> handler.executeTool(env, "drop_everything", Map.of()));
    }
}
