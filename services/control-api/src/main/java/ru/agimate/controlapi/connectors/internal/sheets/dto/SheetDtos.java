package ru.agimate.controlapi.connectors.internal.sheets.dto;

import java.util.List;
import java.util.Map;

/**
 * View/command-модели sheets-коннектора — типы возврата и составные параметры {@code @Tool}-методов
 * (конвенция platform-коннектора). Живут в коннекторном слое, не в {@code controller/**}: record'ы
 * дают рефлектору нормальный {@code outputSchema}, в отличие от {@code Map<String,Object>}.
 * Списки обёрнуты в объект — верхний уровень MCP-результата всегда object.
 */
public final class SheetDtos {

    private SheetDtos() {
    }

    // ===== схема =====

    /** Колонка листа. {@code type}: number|text|date|bool; {@code unit} — пустая строка, если нет. */
    public record ColumnSpec(String name, String title, String type, String unit) {
    }

    public record SheetBrief(String name, String title, List<ColumnSpec> columns, long rows) {
    }

    public record SheetList(List<SheetBrief> sheets) {
    }

    public record SheetDetail(String name, String title, List<ColumnSpec> columns, long rows) {
    }

    // ===== запрос =====

    /**
     * Условие фильтра. {@code op}: eq|ne|gt|gte|lt|lte|contains|is_null|not_null (используют
     * {@code value}) и in|between (используют {@code values}). Значения — всегда строки, приводятся
     * к типу колонки на сервере.
     */
    public record Condition(String column, String op, String value, List<String> values) {
    }

    /** Метрика агрегации: {@code func} — count|sum|avg|min|max ({@code count} игнорирует column). */
    public record Metric(String column, String func) {
    }

    public record RowView(String id, Map<String, Object> values) {
    }

    /** {@code truncated} — упёрлись в лимит: строк больше, сузь фильтр или используй aggregate. */
    public record RowList(String sheet, int count, boolean truncated, List<RowView> rows) {
    }

    public record GroupResult(String key, Map<String, Object> metrics) {
    }

    public record AggregateResult(String sheet, String groupBy, List<GroupResult> groups) {
    }

    // ===== вывод =====

    /** Файл по конвенции docs/connectors/files.md — прикладывается к ответу как {@code [[attach:agf_…]]}. */
    public record FileInfo(String id, String mime, long size) {
    }

    /**
     * Сводка по числовой колонке — O(колонок), а не O(строк): агент не видит собственный PNG,
     * поэтому комментировать график он должен по этим числам, а не по памяти о данных.
     */
    public record ColumnSummary(String column, String unit, long count,
                                Double min, Double max, Double avg, Double sum) {
    }

    public record ChartResult(FileInfo file, String sheet, List<ColumnSummary> summary) {
    }

    public record ExportResult(FileInfo file, int rows) {
    }

    public record ImportResult(String sheet, String title, List<ColumnSpec> columns, int rows) {
    }

    // ===== команды =====

    public record AddResult(boolean ok, int added, List<String> ids) {
    }

    public record OperationResult(boolean ok, String message) {
    }
}
