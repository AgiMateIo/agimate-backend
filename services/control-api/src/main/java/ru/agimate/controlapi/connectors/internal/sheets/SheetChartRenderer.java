package ru.agimate.controlapi.connectors.internal.sheets;

import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.PieChart;
import org.knowm.xchart.PieChartBuilder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Рендер графика листа в PNG (XChart поверх Java2D; Spring Boot держит {@code java.awt.headless=true}).
 *
 * <p>Кириллица в подписях требует шрифтов в образе: логический {@code SansSerif} резолвится через
 * fontconfig, и в «голом» JRE-образе без {@code fontconfig}+DejaVu подписи выйдут квадратами
 * (см. docs/connectors/sheets.md).
 */
@Component
public class SheetChartRenderer {

    public static final String MIME = "image/png";

    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;

    /** Линейный график: X — даты или числа, Y — одна или несколько числовых серий. */
    public byte[] renderLine(String title, String xTitle, String yTitle,
                             List<?> xData, Map<String, List<Double>> series) {
        XYChart chart = new XYChartBuilder()
                .width(WIDTH).height(HEIGHT)
                .title(title == null ? "" : title)
                .xAxisTitle(xTitle == null ? "" : xTitle)
                .yAxisTitle(yTitle == null ? "" : yTitle)
                .build();
        style(chart.getStyler(), series.size());
        chart.getStyler().setDatePattern("dd.MM.yy");
        series.forEach((name, values) -> {
            XYSeries added = chart.addSeries(name, xData, values);
            // Маркеры мешают на длинных рядах и не несут смысла: точки и так на линии.
            added.setMarker(xData.size() > 60 ? SeriesMarkers.NONE : SeriesMarkers.CIRCLE);
        });
        return bytes(chart);
    }

    /** Столбцы: X — категории (результат группировки), Y — числовые серии. */
    public byte[] renderBar(String title, String xTitle, String yTitle,
                            List<String> categories, Map<String, List<Double>> series) {
        CategoryChart chart = new CategoryChartBuilder()
                .width(WIDTH).height(HEIGHT)
                .title(title == null ? "" : title)
                .xAxisTitle(xTitle == null ? "" : xTitle)
                .yAxisTitle(yTitle == null ? "" : yTitle)
                .build();
        style(chart.getStyler(), series.size());
        chart.getStyler().setXAxisLabelRotation(categories.size() > 8 ? 45 : 0);
        series.forEach((name, values) -> chart.addSeries(name, categories, values));
        return bytes(chart);
    }

    /** Доли: одна числовая метрика по категориям. */
    public byte[] renderPie(String title, List<String> categories, List<Double> values) {
        PieChart chart = new PieChartBuilder()
                .width(WIDTH).height(HEIGHT)
                .title(title == null ? "" : title)
                .build();
        chart.getStyler().setLegendPosition(Styler.LegendPosition.OutsideE);
        for (int i = 0; i < categories.size(); i++) {
            Double value = values.get(i);
            if (value != null && value > 0) {
                chart.addSeries(categories.get(i), value);
            }
        }
        if (chart.getSeriesMap().isEmpty()) {
            throw new ConnectorException("Nothing to draw: all values are empty or non-positive");
        }
        return bytes(chart);
    }

    private static void style(Styler styler, int seriesCount) {
        styler.setLegendVisible(seriesCount > 1);
        styler.setLegendPosition(Styler.LegendPosition.OutsideS);
        styler.setChartTitleBoxVisible(false);
        styler.setPlotBorderVisible(false);
    }

    private static byte[] bytes(org.knowm.xchart.internal.chartpart.Chart<?, ?> chart) {
        try {
            return BitmapEncoder.getBitmapBytes(chart, BitmapEncoder.BitmapFormat.PNG);
        } catch (IOException e) {
            throw new ConnectorException("Failed to render chart: " + e.getMessage(), e);
        }
    }
}
