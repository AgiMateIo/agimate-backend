package ru.agimate.controlapi.connectors.internal.sheets;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.AddResult;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.AggregateResult;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.ChartResult;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.ColumnSpec;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.Condition;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.ExportResult;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.FileInfo;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.ImportResult;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.Metric;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.OperationResult;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.RowList;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.RowView;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.SheetDetail;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.SheetList;
import ru.agimate.controlapi.database.entities.Sheet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Тулы sheets-коннектора: таблицы агента с объявленной схемой.
 *
 * <p>Пространство листов личное — владелец резолвится из {@link ConnectorEnv} как {@code agentId}
 * (AGENT scope, как у persistent memory). Все ошибки — {@link ConnectorException}: их текст доходит
 * до агента дословно и написан так, чтобы он починился без участия человека (перечисляет
 * существующие листы, колонки, допустимые операторы).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SheetsToolService {

    /** Импорт разбирает и заливает до 5000 строк — дольше обычного тула, но не минуты. */
    private static final int IMPORT_TIMEOUT_SECONDS = 300;
    /** Экспорт выгружает лист целиком, без капа выдачи агенту. */
    private static final int EXPORT_ROW_CAP = 50_000;

    private final SheetsService sheetsService;
    private final SheetChartService chartService;
    private final SheetFileService fileService;

    // ===== схема =====

    @Tool(name = "list_sheets", title = "Список таблиц",
            description = "List your sheets with their columns (name, title, type, unit) and row counts. "
                    + "Call it when you are unsure what data you already keep — column names from here "
                    + "are the only ones other tools accept.",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public SheetList listSheets() {
        return new SheetList(sheetsService.listSheets(scopeId()));
    }

    @Tool(name = "create_sheet", title = "Создать таблицу",
            description = "Create a sheet with a declared schema. Column 'name' is a latin snake_case "
                    + "slug used by every other tool; 'title' is what the user sees; 'type' is one of "
                    + "number, text, date, bool; 'unit' is a measurement unit or an empty string. "
                    + "Declare a date column if the data has a time dimension — charts and period "
                    + "grouping rely on it.",
            annotations = @ToolAnnotations(openWorldHint = false))
    public SheetDetail createSheet(
            @ToolParam("Sheet machine name, latin snake_case (e.g. household_budget)") String name,
            @ToolParam("Human-readable sheet title (e.g. Бюджет семьи)") String title,
            @ToolParam("Columns: [{\"name\":\"amount\",\"title\":\"Сумма\",\"type\":\"number\",\"unit\":\"₽\"}]")
            List<ColumnSpec> columns) {
        ConnectorEnv env = env();
        return sheetsService.createSheet(scopeId(), env.userId(), name, title, columns);
    }

    @Tool(name = "add_columns", title = "Добавить колонки",
            description = "Add columns to an existing sheet. Prefer this over creating a second sheet "
                    + "when new data belongs to the same table — existing rows simply keep those cells empty.",
            annotations = @ToolAnnotations(openWorldHint = false))
    public SheetDetail addColumns(
            @ToolParam("Sheet name") String sheet,
            @ToolParam("Columns to add, same shape as in create_sheet") List<ColumnSpec> columns) {
        return sheetsService.addColumns(scopeId(), sheet, columns);
    }

    @Tool(name = "delete_sheet", title = "Удалить таблицу",
            description = "Delete a sheet with all its rows. Irreversible — confirm with the user first.",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public OperationResult deleteSheet(
            @ToolParam("Sheet name") String sheet) {
        return sheetsService.deleteSheet(scopeId(), sheet);
    }

    // ===== строки =====

    @Tool(name = "add_rows", title = "Добавить строки",
            description = "Append rows to a sheet. Each row is an object keyed by column name; omit a "
                    + "column to leave the cell empty. Send everything the user dictated in ONE call — "
                    + "up to 500 rows — instead of one call per row.",
            annotations = @ToolAnnotations(openWorldHint = false))
    public AddResult addRows(
            @ToolParam("Sheet name") String sheet,
            @ToolParam("Rows: [{\"date\":\"2026-07-24\",\"amount\":1200,\"category\":\"продукты\"}]")
            List<Map<String, Object>> rows) {
        return sheetsService.addRows(scopeId(), env().userId(), sheet, rows);
    }

    @Tool(name = "update_rows", title = "Изменить строки",
            description = "Overwrite cells in the given rows. Row ids come from query. Only the columns "
                    + "you pass change; an empty value clears the cell.",
            annotations = @ToolAnnotations(openWorldHint = false))
    public OperationResult updateRows(
            @ToolParam("Sheet name") String sheet,
            @ToolParam("Row ids returned by query") List<String> ids,
            @ToolParam("Cells to overwrite: {\"amount\":1350}") Map<String, Object> values) {
        return sheetsService.updateRows(scopeId(), sheet, ids, values);
    }

    @Tool(name = "delete_rows", title = "Удалить строки",
            description = "Delete rows by id (ids come from query). Irreversible.",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public OperationResult deleteRows(
            @ToolParam("Sheet name") String sheet,
            @ToolParam("Row ids returned by query") List<String> ids) {
        return sheetsService.deleteRows(scopeId(), sheet, ids);
    }

    // ===== запросы =====

    @Tool(name = "query", title = "Выбрать строки",
            description = "Read rows with optional filtering and sorting. Filter conditions are ANDed; "
                    + "op is one of eq, ne, gt, gte, lt, lte, contains, in, between, is_null, not_null "
                    + "('value' for scalar ops, 'values' for in/between). Returns row ids needed by "
                    + "update_rows/delete_rows. Capped at 500 rows — if 'truncated' is true, narrow the "
                    + "filter or use aggregate instead of pulling everything.",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public RowList query(
            @ToolParam("Sheet name") String sheet,
            @ToolParam(value = "Conditions: [{\"column\":\"amount\",\"op\":\"gte\",\"value\":\"100\"}]",
                    required = false) List<Condition> filter,
            @ToolParam(value = "Column to sort by (default: insertion order)", required = false) String sortBy,
            @ToolParam(value = "Sort direction: asc or desc", required = false) String sortDir,
            @ToolParam(value = "Max rows to return (default 100, max 500)", required = false) Integer limit) {
        return sheetsService.query(scopeId(), sheet, filter, sortBy, sortDir, limit);
    }

    @Tool(name = "aggregate", title = "Свести данные",
            description = "Compute metrics over rows, optionally grouped by a column — this is how you "
                    + "get totals, averages and breakdowns. NEVER add up rows yourself: pull the number "
                    + "from here. Group by a text column for a breakdown by category, or by a date "
                    + "column with a bucket (day, week, month, year) for a period report.",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public AggregateResult aggregate(
            @ToolParam("Sheet name") String sheet,
            @ToolParam("Metrics: [{\"func\":\"sum\",\"column\":\"amount\"}]. Functions: count, sum, avg, min, max")
            List<Metric> metrics,
            @ToolParam(value = "Column to group by; omit for a single total over all matching rows",
                    required = false) String groupBy,
            @ToolParam(value = "Period bucket for a date groupBy: day, week, month, year (default day)",
                    required = false) String bucket,
            @ToolParam(value = "Same filter shape as in query", required = false) List<Condition> filter) {
        return sheetsService.aggregate(scopeId(), sheet, groupBy, bucket, metrics, filter);
    }

    // ===== вывод =====

    @Tool(name = "render_chart", title = "Построить график",
            description = "Draw a PNG chart and return it as a file id plus a numeric summary of every "
                    + "plotted series. You cannot see the image, so describe it using the returned "
                    + "summary, never from memory. Attach it to your reply with [[attach:agf_…]]. "
                    + "Pass 'aggregate' (e.g. sum) to group by x first — required for pie.",
            annotations = @ToolAnnotations(openWorldHint = false))
    public ChartResult renderChart(
            @ToolParam("Sheet name") String sheet,
            @ToolParam("X axis column: a date column for trends, a text column for categories") String x,
            @ToolParam("Numeric columns to plot") List<String> y,
            @ToolParam(value = "Chart type: line, bar or pie (default: line for a raw date axis, else bar)",
                    required = false) String type,
            @ToolParam(value = "Aggregate rows by x before plotting: count, sum, avg, min, max",
                    required = false) String aggregate,
            @ToolParam(value = "Period bucket when x is a date and aggregate is set: day, week, month, year",
                    required = false) String bucket,
            @ToolParam(value = "Same filter shape as in query", required = false) List<Condition> filter,
            @ToolParam(value = "Chart title (default: sheet title)", required = false) String title) {
        return chartService.render(scopeId(), env().userId(), sheet, type, x, y, aggregate, bucket, filter, title);
    }

    @Tool(name = "export", title = "Выгрузить файл",
            description = "Export a sheet as a real file the user can open or forward: csv (opens in "
                    + "Excel) or xlsx. Returns a file id — attach it with [[attach:agf_…]].",
            annotations = @ToolAnnotations(openWorldHint = false))
    public ExportResult export(
            @ToolParam("Sheet name") String sheet,
            @ToolParam(value = "Format: csv or xlsx (default csv)", required = false) String format,
            @ToolParam(value = "Same filter shape as in query; omit to export everything", required = false)
            List<Condition> filter) {
        UUID scopeId = scopeId();
        Sheet entity = sheetsService.requireSheet(scopeId, sheet);
        List<ColumnSpec> columns = SheetSchema.columns(entity);
        List<RowView> rows = sheetsService.rowsFor(entity, filter, null, null, EXPORT_ROW_CAP);

        String resolved = format == null || format.isBlank() ? "csv" : format.trim().toLowerCase();
        FileInfo file = switch (resolved) {
            case "csv" -> fileService.exportCsv(env().userId(), entity.getName(), columns, rows);
            case "xlsx" -> fileService.exportXlsx(env().userId(), entity.getName(), entity.getTitle(),
                    columns, rows);
            default -> throw new ConnectorException("Invalid format: '" + format + "'. Allowed: csv, xlsx");
        };
        return new ExportResult(file, rows.size());
    }

    @Tool(name = "import_file", title = "Импорт таблицы",
            description = "Create a sheet from a spreadsheet the user sent (xlsx or csv file id). The "
                    + "first row is treated as headers: each becomes a column whose title is the original "
                    + "header and whose type is inferred from the data. Use it when the user already "
                    + "keeps the data in a file instead of retyping it.",
            annotations = @ToolAnnotations(openWorldHint = false),
            timeoutSeconds = IMPORT_TIMEOUT_SECONDS)
    public ImportResult importFile(
            @ToolParam("File id of the xlsx/csv the user sent (agf_…)") String fileId,
            @ToolParam("Machine name for the new sheet, latin snake_case") String sheet,
            @ToolParam(value = "Human-readable sheet title (default: the sheet name)", required = false)
            String title) {
        ConnectorEnv env = env();
        SheetFileService.ParsedTable table = fileService.parse(env.userId(), fileId);
        if (table.headers().isEmpty()) {
            throw new ConnectorException("No header row found in the file");
        }
        List<ColumnSpec> columns = fileService.inferColumns(table);
        List<Map<String, Object>> cells = new ArrayList<>(table.rows().size());
        for (List<String> row : table.rows()) {
            cells.add(fileService.toCells(columns, row));
        }
        SheetDetail created = sheetsService.importSheet(scopeId(), env.userId(), sheet, title, columns, cells);
        if (table.truncated()) {
            log.info("Import of {} truncated at {} rows", fileId, SheetFileService.MAX_IMPORT_ROWS);
        }
        return new ImportResult(created.name(), created.title(), created.columns(), (int) created.rows());
    }

    // ===== контекст =====

    private static ConnectorEnv env() {
        return ConnectorEnvHolder.current();
    }

    /** Пространство листов личное: владелец — вызывающий агент. */
    private static UUID scopeId() {
        ConnectorEnv env = env();
        if (env.agentId() == null) {
            throw new ConnectorException("sheets tools require an agent context");
        }
        return env.agentId();
    }
}
