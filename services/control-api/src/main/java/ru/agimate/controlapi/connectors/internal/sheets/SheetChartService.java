package ru.agimate.controlapi.connectors.internal.sheets;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.AggregateResult;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.ChartResult;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.ColumnSpec;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.ColumnSummary;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.Condition;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.FileInfo;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.GroupResult;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.Metric;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.RowView;
import ru.agimate.controlapi.database.entities.Sheet;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Сборка графика: выбор данных (сырые строки либо сводка), рендер PNG и укладка в файловый слой.
 *
 * <p>Вместе с картинкой возвращается {@link ColumnSummary} по каждой серии — агент собственный PNG
 * не видит, и без чисел рядом он комментировал бы график по памяти о том, что в него отправлял.
 */
@Component
@RequiredArgsConstructor
public class SheetChartService {

    /** Кап точек на сыром графике: дальше линия всё равно нечитаема, а выборка тяжелеет. */
    private static final int MAX_POINTS = 2000;

    private final SheetsService sheetsService;
    private final SheetChartRenderer renderer;
    private final SheetFileService fileService;

    public ChartResult render(UUID scopeId, UUID userId, String sheetName, String type, String xColumn,
                              List<String> yColumns, String aggregateFunc, String bucket,
                              List<Condition> filter, String title) {
        Sheet sheet = sheetsService.requireSheet(scopeId, sheetName);
        List<ColumnSpec> columns = SheetSchema.columns(sheet);

        ColumnSpec x = SheetSchema.require(columns, requireText(xColumn, "x"), sheet.getName());
        if (yColumns == null || yColumns.isEmpty()) {
            throw new ConnectorException("Parameter 'y' is required — one or more numeric columns to plot");
        }
        List<ColumnSpec> ys = new ArrayList<>(yColumns.size());
        for (String name : yColumns) {
            ColumnSpec column = SheetSchema.require(columns, requireText(name, "y"), sheet.getName());
            if (SheetSchema.typeOf(column) != SheetColumnType.NUMBER) {
                throw new ConnectorException("Column '" + column.name() + "' is " + column.type()
                        + ", but a chart plots numeric columns. Numeric columns here: " + numericNames(columns));
            }
            ys.add(column);
        }

        boolean grouped = aggregateFunc != null && !aggregateFunc.isBlank();
        String chartType = resolveType(type, x, grouped);
        if ("pie".equals(chartType)) {
            if (!grouped) {
                throw new ConnectorException("A pie chart needs 'aggregate' (e.g. sum) to build shares");
            }
            if (ys.size() != 1) {
                throw new ConnectorException("A pie chart takes exactly one 'y' column");
            }
        }

        Plot plot = grouped
                ? groupedPlot(scopeId, sheet, x, ys, aggregateFunc, bucket, filter)
                : rawPlot(sheet, x, ys, filter);
        if (plot.series().isEmpty() || plot.size() == 0) {
            throw new ConnectorException("No data to plot: the filter matched no rows with values in "
                    + String.join(", ", yColumns));
        }

        String chartTitle = title == null || title.isBlank() ? sheet.getTitle() : title;
        byte[] png = switch (chartType) {
            case "line" -> renderer.renderLine(chartTitle, x.title(), unitOf(ys), plot.xData(), plot.series());
            case "pie" -> renderer.renderPie(chartTitle, plot.labels(), plot.series().values().iterator().next());
            default -> renderer.renderBar(chartTitle, x.title(), unitOf(ys), plot.labels(), plot.series());
        };

        FileInfo file = fileService.store(userId, "chart", SheetChartRenderer.MIME, png);
        return new ChartResult(file, sheet.getName(), summaries(ys, plot));
    }

    // ===== подготовка данных =====

    /** Данные графика: ось X (даты/числа для линии либо подписи для столбцов) и серии значений. */
    private record Plot(List<?> xData, List<String> labels, Map<String, List<Double>> series) {
        int size() {
            return labels.size();
        }
    }

    private Plot rawPlot(Sheet sheet, ColumnSpec x, List<ColumnSpec> ys, List<Condition> filter) {
        List<RowView> rows = sheetsService.rowsFor(sheet, filter, x.name(), "asc", MAX_POINTS);
        List<Object> xData = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        Map<String, List<Double>> series = new LinkedHashMap<>();
        ys.forEach(y -> series.put(y.title(), new ArrayList<>()));

        for (RowView row : rows) {
            Object rawX = row.values().get(x.name());
            if (rawX == null || ys.stream().anyMatch(y -> row.values().get(y.name()) == null)) {
                // Точка без X или с дырой в любой из серий сдвинула бы остальные — пропускаем целиком.
                continue;
            }
            xData.add(axisValue(x, rawX));
            labels.add(label(x, rawX));
            for (ColumnSpec y : ys) {
                series.get(y.title()).add(SheetSchema.number(row.values().get(y.name()), y.name()).doubleValue());
            }
        }
        return new Plot(xData, labels, series);
    }

    private Plot groupedPlot(UUID scopeId, Sheet sheet, ColumnSpec x, List<ColumnSpec> ys,
                             String func, String bucket, List<Condition> filter) {
        List<Metric> metrics = ys.stream().map(y -> new Metric(y.name(), func)).toList();
        AggregateResult aggregate =
                sheetsService.aggregate(scopeId, sheet.getName(), x.name(), bucket, metrics, filter);

        List<String> labels = new ArrayList<>();
        Map<String, List<Double>> series = new LinkedHashMap<>();
        ys.forEach(y -> series.put(y.title(), new ArrayList<>()));
        for (GroupResult group : aggregate.groups()) {
            labels.add(group.key() == null ? "—" : group.key());
            for (ColumnSpec y : ys) {
                Object value = group.metrics().get(func.trim().toLowerCase() + "_" + y.name());
                series.get(y.title()).add(value instanceof Number number ? number.doubleValue() : 0d);
            }
        }
        return new Plot(labels, labels, series);
    }

    // ===== мелочи =====

    private static String resolveType(String type, ColumnSpec x, boolean grouped) {
        if (type == null || type.isBlank()) {
            return !grouped && SheetSchema.typeOf(x) == SheetColumnType.DATE ? "line" : "bar";
        }
        String resolved = type.trim().toLowerCase();
        if (!List.of("line", "bar", "pie").contains(resolved)) {
            throw new ConnectorException("Invalid chart type: '" + type + "'. Allowed: line, bar, pie");
        }
        return resolved;
    }

    /** Значение оси X для линии: дата — как Date (XChart сам подпишет), число — как Double. */
    private static Object axisValue(ColumnSpec x, Object raw) {
        return switch (SheetSchema.typeOf(x)) {
            case DATE -> Date.from(SheetSchema.dateTime(raw, x.name())
                    .atZone(ZoneId.systemDefault()).toInstant());
            case NUMBER -> SheetSchema.number(raw, x.name()).doubleValue();
            default -> String.valueOf(raw);
        };
    }

    private static String label(ColumnSpec x, Object raw) {
        if (SheetSchema.typeOf(x) == SheetColumnType.DATE) {
            return SheetSchema.dateTime(raw, x.name()).toLocalDate().toString();
        }
        return String.valueOf(raw);
    }

    private static List<ColumnSummary> summaries(List<ColumnSpec> ys, Plot plot) {
        List<ColumnSummary> summaries = new ArrayList<>(ys.size());
        for (ColumnSpec y : ys) {
            List<Double> values = plot.series().get(y.title());
            if (values == null || values.isEmpty()) {
                continue;
            }
            double sum = 0;
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (Double value : values) {
                sum += value;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            summaries.add(new ColumnSummary(y.name(), y.unit(), values.size(),
                    round(min), round(max), round(sum / values.size()), round(sum)));
        }
        return summaries;
    }

    private static Double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }

    private static String unitOf(List<ColumnSpec> ys) {
        String unit = ys.getFirst().unit();
        boolean same = ys.stream().allMatch(y -> y.unit().equals(unit));
        return same && !unit.isBlank() ? unit : "";
    }

    private static String numericNames(List<ColumnSpec> columns) {
        List<String> names = columns.stream()
                .filter(c -> SheetSchema.typeOf(c) == SheetColumnType.NUMBER)
                .map(ColumnSpec::name)
                .toList();
        return names.isEmpty() ? "(none)" : String.join(", ", names);
    }

    private static String requireText(String value, String parameter) {
        if (value == null || value.isBlank()) {
            throw new ConnectorException("Parameter '" + parameter + "' is required");
        }
        return value.trim();
    }
}
