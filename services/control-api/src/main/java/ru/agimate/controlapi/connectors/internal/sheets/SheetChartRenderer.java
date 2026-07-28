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
 * Rendering of a sheet's chart into PNG (XChart over Java2D; Spring Boot keeps
 * {@code java.awt.headless=true}).
 *
 * <p>Cyrillic in the labels requires fonts inside the image: the logical {@code SansSerif} is
 * resolved through fontconfig, and in a bare JRE image without {@code fontconfig} and DejaVu the
 * labels come out as boxes (see docs/connectors/sheets.md).
 */
@Component
public class SheetChartRenderer {

    public static final String MIME = "image/png";

    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;

    /** Line chart: X is dates or numbers, Y is one or more numeric series. */
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
            // Markers get in the way on long series and add nothing: the points are on the line already.
            added.setMarker(xData.size() > 60 ? SeriesMarkers.NONE : SeriesMarkers.CIRCLE);
        });
        return bytes(chart);
    }

    /** Bars: X is categories (the result of grouping), Y is numeric series. */
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

    /** Shares: one numeric metric across categories. */
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
