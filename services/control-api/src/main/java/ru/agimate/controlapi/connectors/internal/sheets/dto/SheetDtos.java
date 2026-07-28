package ru.agimate.controlapi.connectors.internal.sheets.dto;

import java.util.List;
import java.util.Map;

/**
 * View and command models of the sheets connector — the return types and composite parameters of its
 * {@code @Tool} methods (the platform connector's convention). They live in the connector layer, not
 * in {@code controller/**}: records give the reflector a proper {@code outputSchema}, unlike
 * {@code Map<String,Object>}. Lists are wrapped in an object — the top level of an MCP result is
 * always an object.
 */
public final class SheetDtos {

    private SheetDtos() {
    }

    // ===== schema =====

    /** A sheet's column. {@code type}: number|text|date|bool; {@code unit} is an empty string when absent. */
    public record ColumnSpec(String name, String title, String type, String unit) {
    }

    public record SheetBrief(String name, String title, List<ColumnSpec> columns, long rows) {
    }

    public record SheetList(List<SheetBrief> sheets) {
    }

    public record SheetDetail(String name, String title, List<ColumnSpec> columns, long rows) {
    }

    // ===== query =====

    /**
     * A filter condition. {@code op}: eq|ne|gt|gte|lt|lte|contains|is_null|not_null (these use
     * {@code value}) and in|between (these use {@code values}). Values are always strings and are
     * coerced to the column's type on the server.
     */
    public record Condition(String column, String op, String value, List<String> values) {
    }

    /** An aggregation metric: {@code func} is count|sum|avg|min|max ({@code count} ignores column). */
    public record Metric(String column, String func) {
    }

    public record RowView(String id, Map<String, Object> values) {
    }

    /** {@code truncated} — we hit the limit: there are more rows, so narrow the filter or use aggregate. */
    public record RowList(String sheet, int count, boolean truncated, List<RowView> rows) {
    }

    public record GroupResult(String key, Map<String, Object> metrics) {
    }

    public record AggregateResult(String sheet, String groupBy, List<GroupResult> groups) {
    }

    // ===== output =====

    /** A file by the docs/connectors/files.md convention — attached to the answer as {@code [[attach:agf_…]]}. */
    public record FileInfo(String id, String mime, long size) {
    }

    /**
     * Summary of a numeric column — O(columns), not O(rows): the agent cannot see its own PNG, so it
     * must comment on the chart from these numbers rather than from memory of the data.
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

    // ===== commands =====

    public record AddResult(boolean ok, int added, List<String> ids) {
    }

    public record OperationResult(boolean ok, String message) {
    }
}
