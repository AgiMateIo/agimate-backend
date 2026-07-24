package ru.agimate.controlapi.connectors.internal.sheets;

import lombok.RequiredArgsConstructor;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.ColumnSpec;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.FileInfo;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.RowView;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.FileRejectedException;
import ru.agimate.controlapi.storage.FileStorageService;
import ru.agimate.controlapi.storage.StoredFileNotFoundException;
import ru.agimate.controlapi.database.entities.StoredFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Импорт и экспорт таблиц: файл — это края потока, а не его середина. Внутри платформы истина живёт
 * в БД (быстрые агрегаты, детерминированные метрики), а xlsx/csv нужен на входе («у меня уже всё
 * в табличке») и на выходе («отдай бухгалтеру/врачу»).
 */
@Component
@RequiredArgsConstructor
public class SheetFileService {

    public static final String CSV_MIME = "text/csv";
    public static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Сколько строк смотрим, чтобы определить тип колонки при импорте. */
    private static final int TYPE_SAMPLE_ROWS = 50;
    public static final int MAX_IMPORT_ROWS = 5000;
    private static final int MAX_IMPORT_COLUMNS = SheetSchema.MAX_COLUMNS;

    private static final DateTimeFormatter OUT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final FileStorageService fileStorageService;

    /** Разобранная входная таблица: заголовки + сырые строки ячеек. */
    public record ParsedTable(List<String> headers, List<List<String>> rows, boolean truncated) {
    }

    // ===== импорт =====

    public ParsedTable parse(UUID userId, String fileId) {
        byte[] content = read(userId, fileId);
        return isZip(content) ? parseXlsx(content) : parseCsv(content);
    }

    /**
     * Схема по разобранной таблице: заголовок → {@code name} (транслит-slug) + {@code title}
     * (исходный текст), тип — по фактическим значениям колонки.
     */
    public List<ColumnSpec> inferColumns(ParsedTable table) {
        List<ColumnSpec> columns = new ArrayList<>();
        List<String> taken = new ArrayList<>();
        for (int index = 0; index < table.headers().size(); index++) {
            String header = table.headers().get(index);
            String title = header == null || header.isBlank() ? "Column " + (index + 1) : header.trim();
            String name = SheetSlugs.unique(title, taken, "column_" + (index + 1));
            taken.add(name);
            columns.add(new ColumnSpec(name, title, inferType(table.rows(), index).wire(), ""));
        }
        return columns;
    }

    public Map<String, Object> toCells(List<ColumnSpec> columns, List<String> raw) {
        Map<String, Object> cells = new LinkedHashMap<>();
        for (int index = 0; index < columns.size() && index < raw.size(); index++) {
            ColumnSpec column = columns.get(index);
            Object value = SheetSchema.coerceCell(column, raw.get(index));
            if (value != null) {
                cells.put(column.name(), value);
            }
        }
        return cells;
    }

    // ===== экспорт =====

    public FileInfo exportCsv(UUID userId, String sheetName, List<ColumnSpec> columns, List<RowView> rows) {
        StringBuilder csv = new StringBuilder();
        // BOM + ';' — иначе русский Excel открывает UTF-8 как кракозябры и склеивает всё в одну колонку.
        csv.append('﻿');
        csv.append(String.join(";", columns.stream().map(c -> quote(c.title())).toList())).append("\r\n");
        for (RowView row : rows) {
            List<String> cells = new ArrayList<>(columns.size());
            for (ColumnSpec column : columns) {
                cells.add(quote(display(column, row.values().get(column.name()))));
            }
            csv.append(String.join(";", cells)).append("\r\n");
        }
        return store(userId, sheetName + ".csv", CSV_MIME,
                csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    public FileInfo exportXlsx(UUID userId, String sheetName, String sheetTitle,
                               List<ColumnSpec> columns, List<RowView> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Workbook workbook = new Workbook(out, "AgiMate", "1.0");
            Worksheet worksheet = workbook.newWorksheet(excelSheetName(sheetTitle));
            for (int column = 0; column < columns.size(); column++) {
                worksheet.value(0, column, columns.get(column).title());
                worksheet.width(column, 18);
            }
            for (int index = 0; index < rows.size(); index++) {
                Map<String, Object> values = rows.get(index).values();
                for (int column = 0; column < columns.size(); column++) {
                    ColumnSpec spec = columns.get(column);
                    writeCell(worksheet, index + 1, column, spec, values.get(spec.name()));
                }
            }
            workbook.finish();
        } catch (IOException e) {
            throw new ConnectorException("Failed to write xlsx: " + e.getMessage(), e);
        }
        return store(userId, sheetName + ".xlsx", XLSX_MIME, out.toByteArray());
    }

    // ===== файловый слой =====

    public FileInfo store(UUID userId, String origin, String mime, byte[] content) {
        try {
            StoredFile file = fileStorageService.store(userId, "sheets:" + origin, mime, content.length,
                    new ByteArrayInputStream(content), null);
            return new FileInfo(FileIds.external(file.getId()), file.getMime(), file.getSizeBytes());
        } catch (FileRejectedException e) {
            throw new ConnectorException(e.getMessage(), e);
        }
    }

    private byte[] read(UUID userId, String fileId) {
        if (fileId == null || FileIds.parse(fileId).isEmpty()) {
            throw new ConnectorException("Invalid file id: '" + fileId + "'. Expected agf_<uuid>");
        }
        try (InputStream content = fileStorageService.open(userId, fileId).content()) {
            return content.readAllBytes();
        } catch (StoredFileNotFoundException e) {
            throw new ConnectorException("File " + fileId + " not found", e);
        } catch (IOException e) {
            throw new ConnectorException("Failed to read file " + fileId + ": " + e.getMessage(), e);
        }
    }

    // ===== разбор =====

    private ParsedTable parseXlsx(byte[] content) {
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(content));
             Stream<Row> stream = workbook.getFirstSheet().openStream()) {
            List<Row> rows = stream.limit(MAX_IMPORT_ROWS + 2L).toList();
            if (rows.isEmpty()) {
                throw new ConnectorException("The spreadsheet is empty");
            }
            Row header = rows.getFirst();
            int width = Math.min(header.getCellCount(), MAX_IMPORT_COLUMNS);
            List<String> headers = new ArrayList<>(width);
            for (int index = 0; index < width; index++) {
                headers.add(header.getCellAsString(index).orElse("").trim());
            }
            List<List<String>> data = new ArrayList<>();
            for (int index = 1; index < rows.size() && data.size() < MAX_IMPORT_ROWS; index++) {
                List<String> cells = readRow(rows.get(index), width);
                if (cells.stream().anyMatch(cell -> !cell.isBlank())) {
                    data.add(cells);
                }
            }
            return new ParsedTable(headers, data, rows.size() > MAX_IMPORT_ROWS + 1);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ConnectorException connectorException) {
                throw connectorException;
            }
            throw new ConnectorException("Failed to read the spreadsheet: " + e.getMessage(), e);
        }
    }

    private static List<String> readRow(Row row, int width) {
        List<String> cells = new ArrayList<>(width);
        for (int index = 0; index < width; index++) {
            cells.add(cellText(row, index));
        }
        return cells;
    }

    /** Ячейка в текст: числа и даты берём типизированно, иначе Excel отдаёт «45123» вместо даты. */
    private static String cellText(Row row, int index) {
        Optional<LocalDateTime> date = safeDate(row, index);
        if (date.isPresent()) {
            return date.get().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        Optional<BigDecimal> number = safeNumber(row, index);
        if (number.isPresent()) {
            return number.get().stripTrailingZeros().toPlainString();
        }
        return row.getCellAsString(index).orElse("").trim();
    }

    private static Optional<LocalDateTime> safeDate(Row row, int index) {
        try {
            return row.getCellAsDate(index);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<BigDecimal> safeNumber(Row row, int index) {
        try {
            return row.getCellAsNumber(index);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private ParsedTable parseCsv(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        List<List<String>> all = new ArrayList<>();
        char separator = detectSeparator(text);
        for (String line : text.split("\r?\n")) {
            if (!line.isBlank()) {
                all.add(splitCsvLine(line, separator));
            }
        }
        if (all.isEmpty()) {
            throw new ConnectorException("The CSV file is empty");
        }
        List<String> headers = all.getFirst().stream().limit(MAX_IMPORT_COLUMNS).toList();
        List<List<String>> data = new ArrayList<>();
        for (int index = 1; index < all.size() && data.size() < MAX_IMPORT_ROWS; index++) {
            List<String> row = all.get(index);
            List<String> cells = new ArrayList<>(headers.size());
            for (int column = 0; column < headers.size(); column++) {
                cells.add(column < row.size() ? row.get(column) : "");
            }
            data.add(cells);
        }
        return new ParsedTable(headers, data, all.size() - 1 > MAX_IMPORT_ROWS);
    }

    private static char detectSeparator(String text) {
        String head = text.split("\r?\n", 2)[0];
        long semicolons = head.chars().filter(c -> c == ';').count();
        long commas = head.chars().filter(c -> c == ',').count();
        long tabs = head.chars().filter(c -> c == '\t').count();
        if (tabs > semicolons && tabs > commas) {
            return '\t';
        }
        return commas > semicolons ? ',' : ';';
    }

    private static List<String> splitCsvLine(String line, char separator) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char symbol = line.charAt(index);
            if (quoted) {
                if (symbol == '"') {
                    if (index + 1 < line.length() && line.charAt(index + 1) == '"') {
                        cell.append('"');
                        index++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(symbol);
                }
            } else if (symbol == '"') {
                quoted = true;
            } else if (symbol == separator) {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(symbol);
            }
        }
        cells.add(cell.toString().trim());
        return cells;
    }

    // ===== типы и форматирование =====

    private static SheetColumnType inferType(List<List<String>> rows, int index) {
        boolean sawValue = false;
        boolean number = true;
        boolean date = true;
        boolean bool = true;
        int seen = 0;
        for (List<String> row : rows) {
            if (index >= row.size() || row.get(index).isBlank() || seen >= TYPE_SAMPLE_ROWS) {
                continue;
            }
            seen++;
            sawValue = true;
            String value = row.get(index);
            number &= parses(() -> SheetSchema.number(value, "x"));
            date &= parses(() -> SheetSchema.dateTime(value, "x"));
            bool &= List.of("true", "false", "да", "нет", "yes", "no", "0", "1")
                    .contains(value.trim().toLowerCase());
        }
        if (!sawValue) {
            return SheetColumnType.TEXT;
        }
        // Порядок важен: «1»/«0» проходят и как число, и как bool — числовая трактовка безопаснее.
        if (number) {
            return SheetColumnType.NUMBER;
        }
        if (date) {
            return SheetColumnType.DATE;
        }
        return bool ? SheetColumnType.BOOL : SheetColumnType.TEXT;
    }

    private static boolean parses(Runnable attempt) {
        try {
            attempt.run();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void writeCell(Worksheet worksheet, int row, int column, ColumnSpec spec, Object value) {
        if (value == null) {
            return;
        }
        switch (SheetSchema.typeOf(spec)) {
            case NUMBER -> worksheet.value(row, column, SheetSchema.number(value, spec.name()));
            case BOOL -> worksheet.value(row, column, Boolean.valueOf(String.valueOf(value)));
            case DATE -> worksheet.value(row, column, SheetSchema.dateTime(value, spec.name()));
            case TEXT -> worksheet.value(row, column, String.valueOf(value));
        }
    }

    private static String display(ColumnSpec column, Object value) {
        if (value == null) {
            return "";
        }
        if (SheetSchema.typeOf(column) == SheetColumnType.DATE) {
            return SheetSchema.dateTime(value, column.name()).format(OUT_DATE);
        }
        return String.valueOf(value);
    }

    private static String quote(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(';') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    /** Имя листа Excel: без {@code []:*?/\}, не длиннее 31 символа. */
    private static String excelSheetName(String title) {
        String cleaned = (title == null || title.isBlank() ? "Sheet" : title)
                .replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }

    private static boolean isZip(byte[] content) {
        return content.length > 3 && content[0] == 'P' && content[1] == 'K';
    }
}
