package ru.agimate.controlapi.connectors.internal.sheets;

import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.ColumnSpec;
import ru.agimate.controlapi.database.entities.Sheet;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A sheet's schema: parsing and assembling columns, and coercing cell values.
 *
 * <p>The key invariant {@link SheetQueryBuilder} relies on: a value reaches JSONB only after passing
 * {@link #coerceCell}, so a numeric column holds a JSON number or has no key at all (an empty string
 * is never written). Because of that, {@code (data->>'col')::numeric} in aggregation cannot trip over
 * garbage — NULL casts to NULL.
 */
@UtilityClass
public class SheetSchema {

    /** Machine code of a column or sheet: it is substituted into SQL as a JSONB key, hence strictly ASCII. */
    public static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,47}$");

    public static final int MAX_COLUMNS = 32;

    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"));

    // ===== columns =====

    public static List<ColumnSpec> columns(Sheet sheet) {
        List<ColumnSpec> specs = new ArrayList<>();
        for (Map<String, Object> raw : sheet.getColumns()) {
            specs.add(new ColumnSpec(
                    str(raw.get("name")),
                    str(raw.get("title")),
                    str(raw.get("type")),
                    raw.get("unit") == null ? "" : str(raw.get("unit"))));
        }
        return specs;
    }

    public static List<Map<String, Object>> toStorage(List<ColumnSpec> columns) {
        List<Map<String, Object>> raw = new ArrayList<>(columns.size());
        for (ColumnSpec column : columns) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", column.name());
            item.put("title", column.title());
            item.put("type", column.type());
            item.put("unit", column.unit() == null ? "" : column.unit());
            raw.add(item);
        }
        return raw;
    }

    /** Normalise a column declaration: a slug name, a non-empty title, a known type. */
    public static ColumnSpec normalize(ColumnSpec column) {
        if (column == null || column.name() == null) {
            throw new ConnectorException("Column declaration requires 'name'");
        }
        String name = column.name().trim();
        requireName(name, "column name");
        SheetColumnType type = SheetColumnType.parse(column.type());
        String title = column.title() == null || column.title().isBlank() ? name : column.title().trim();
        String unit = column.unit() == null ? "" : column.unit().trim();
        return new ColumnSpec(name, title, type.wire(), unit);
    }

    public static void requireName(String name, String what) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new ConnectorException("Invalid " + what + ": '" + name
                    + "'. Use a latin snake_case slug: start with a letter, then letters/digits/underscore, "
                    + "up to 48 chars (e.g. total_amount)");
        }
    }

    /** The column by name; otherwise an error listing the existing ones, so the agent can fix itself. */
    public static ColumnSpec require(List<ColumnSpec> columns, String name, String sheetName) {
        for (ColumnSpec column : columns) {
            if (column.name().equals(name)) {
                return column;
            }
        }
        throw new ConnectorException("Sheet '" + sheetName + "' has no column '" + name
                + "'. Existing columns: " + names(columns)
                + ". Use add_columns to add a new one");
    }

    public static SheetColumnType typeOf(ColumnSpec column) {
        return SheetColumnType.parse(column.type());
    }

    public static String names(List<ColumnSpec> columns) {
        return columns.isEmpty() ? "(none)"
                : columns.stream().map(ColumnSpec::name).reduce((a, b) -> a + ", " + b).orElse("");
    }

    // ===== values =====

    /**
     * A cell value in the column's JSON representation. {@code null} means the cell is empty: the key
     * is not written into JSONB at all (see the invariant in the class javadoc).
     */
    public static Object coerceCell(ColumnSpec column, Object raw) {
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            return null;
        }
        return switch (typeOf(column)) {
            case NUMBER -> number(raw, column.name());
            case DATE -> dateTime(raw, column.name()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            case BOOL -> bool(raw, column.name());
            case TEXT -> String.valueOf(raw);
        };
    }

    /** A filter value as a SQL bind parameter of the right type. */
    public static Object coerceParam(ColumnSpec column, String raw) {
        if (raw == null) {
            throw new ConnectorException("Filter on column '" + column.name() + "' requires a value");
        }
        return switch (typeOf(column)) {
            case NUMBER -> number(raw, column.name());
            case DATE -> dateTime(raw, column.name());
            case BOOL -> bool(raw, column.name());
            case TEXT -> raw;
        };
    }

    public static BigDecimal number(Object raw, String column) {
        if (raw instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        // A user dictates «1 200,50» — we accept spaces as separators and a comma as the decimal point.
        String cleaned = String.valueOf(raw).replace(" ", "").replace(" ", "").replace(',', '.');
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new ConnectorException("Column '" + column + "' is numeric, but got: '" + raw + "'");
        }
    }

    public static LocalDateTime dateTime(Object raw, String column) {
        if (raw instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (raw instanceof LocalDate ld) {
            return ld.atStartOfDay();
        }
        String value = String.valueOf(raw).trim();
        for (DateTimeFormatter format : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // the next format
            }
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                // the next format
            }
        }
        throw new ConnectorException("Column '" + column + "' is a date, but got: '" + raw
                + "'. Use 2026-07-24 or 2026-07-24T08:30");
    }

    private static Boolean bool(Object raw, String column) {
        if (raw instanceof Boolean b) {
            return b;
        }
        String value = String.valueOf(raw).trim().toLowerCase();
        return switch (value) {
            case "true", "yes", "1", "да" -> Boolean.TRUE;
            case "false", "no", "0", "нет" -> Boolean.FALSE;
            default -> throw new ConnectorException("Column '" + column + "' is boolean, but got: '" + raw + "'");
        };
    }

    private static String str(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }
}
