package ru.agimate.controlapi.connectors.internal.sheets;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.ColumnSpec;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.Condition;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.GroupResult;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.Metric;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.RowView;
import ru.agimate.controlapi.database.entities.Sheet;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Translation of the structured query DSL (filter/sort/groupBy/metrics) into SQL over the JSONB
 * column.
 *
 * <p><b>Why a DSL and not SQL from the LLM.</b> Raw SQL would have to be validated so the model could
 * not reach other people's tables or bring the database down with a heavy query — and validating SQL
 * means parsing SQL. The DSL protects structurally instead: <b>a column name arrives from the LLM but
 * is resolved against the sheet's declared schema (a whitelist), and every value leaves as a bind
 * parameter</b>. Only a name that already passed {@link SheetSchema#NAME} ever reaches the SQL string
 * — injection is impossible by construction, not by filtering.
 *
 * <p>The {@code ::numeric}/{@code ::timestamp} casts are safe thanks to the write invariant
 * ({@link SheetSchema#coerceCell}): a typed column holds a value of its own type or has no key at
 * all, and {@code NULL::numeric} is NULL, not an error.
 */
@Component
public class SheetQueryBuilder {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;
    /** Cap on the number of groups: a summary over hundreds of categories is useless to the agent and bloats the context. */
    public static final int MAX_GROUPS = 200;

    private static final String BASE = "select %s from sheet_rows where sheet_id = ?1";

    @PersistenceContext
    private EntityManager entityManager;

    /** Result of a selection: the rows plus a flag that we hit the limit. */
    public record Selection(List<RowView> rows, boolean truncated) {
    }

    // ===== row selection =====

    public Selection select(Sheet sheet, List<ColumnSpec> columns, List<Condition> filter,
                            String sortBy, String sortDir, Integer limit) {
        int cap = normalizeLimit(limit);
        StringBuilder sql = new StringBuilder(BASE.formatted("id, data"));
        List<Object> params = new ArrayList<>();
        params.add(sheet.getId());
        appendFilter(sql, params, columns, sheet.getName(), filter);

        if (sortBy != null && !sortBy.isBlank()) {
            ColumnSpec sortColumn = SheetSchema.require(columns, sortBy.trim(), sheet.getName());
            sql.append(" order by ").append(expr(sortColumn)).append(direction(sortDir)).append(" nulls last");
        } else {
            sql.append(" order by created_at asc");
        }
        // One more than requested — that tells us «is there more» without a separate count.
        sql.append(" limit ").append(cap + 1);

        Query query = bind(sql.toString(), params);
        List<?> raw = query.getResultList();
        boolean truncated = raw.size() > cap;
        List<RowView> rows = new ArrayList<>(Math.min(raw.size(), cap));
        for (int i = 0; i < Math.min(raw.size(), cap); i++) {
            Object[] row = (Object[]) raw.get(i);
            rows.add(new RowView(String.valueOf(row[0]), readJson(row[1])));
        }
        return new Selection(rows, truncated);
    }

    // ===== aggregation =====

    public List<GroupResult> aggregate(Sheet sheet, List<ColumnSpec> columns, String groupBy, String bucket,
                                       List<Metric> metrics, List<Condition> filter) {
        if (metrics == null || metrics.isEmpty()) {
            throw new ConnectorException("At least one metric is required, e.g. "
                    + "[{\"func\":\"sum\",\"column\":\"amount\"}]. Functions: count, sum, avg, min, max");
        }
        List<String> metricNames = new ArrayList<>(metrics.size());
        StringBuilder projection = new StringBuilder();

        String groupExpr = null;
        if (groupBy != null && !groupBy.isBlank()) {
            ColumnSpec groupColumn = SheetSchema.require(columns, groupBy.trim(), sheet.getName());
            groupExpr = groupExpr(groupColumn, bucket);
            projection.append(groupExpr).append(" as grp");
        }
        for (Metric metric : metrics) {
            if (!projection.isEmpty()) {
                projection.append(", ");
            }
            projection.append(metricExpr(metric, columns, sheet.getName(), metricNames));
        }

        StringBuilder sql = new StringBuilder(BASE.formatted(projection.toString()));
        List<Object> params = new ArrayList<>();
        params.add(sheet.getId());
        appendFilter(sql, params, columns, sheet.getName(), filter);
        if (groupExpr != null) {
            sql.append(" group by 1 order by 1 limit ").append(MAX_GROUPS);
        }

        List<?> raw = bind(sql.toString(), params).getResultList();
        List<GroupResult> groups = new ArrayList<>(raw.size());
        int offset = groupExpr == null ? 0 : 1;
        for (Object item : raw) {
            Object[] row = item instanceof Object[] array ? array : new Object[]{item};
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < metricNames.size(); i++) {
                values.put(metricNames.get(i), jsonNumber(row[offset + i]));
            }
            groups.add(new GroupResult(offset == 0 ? null : asKey(row[0]), values));
        }
        return groups;
    }

    // ===== expression assembly =====

    /** Expression for reading a column. The name already passed the schema's whitelist — only it goes into the SQL. */
    private static String expr(ColumnSpec column) {
        String name = column.name();
        if (!SheetSchema.NAME.matcher(name).matches()) {
            throw new ConnectorException("Malformed column name in sheet schema: '" + name + "'");
        }
        String path = "data->>'" + name + "'";
        return switch (SheetSchema.typeOf(column)) {
            case NUMBER -> "(" + path + ")::numeric";
            case DATE -> "(" + path + ")::timestamp";
            case BOOL -> "(" + path + ")::boolean";
            case TEXT -> path;
        };
    }

    private static String groupExpr(ColumnSpec column, String bucket) {
        if (SheetSchema.typeOf(column) != SheetColumnType.DATE) {
            return "(" + expr(column) + ")::text";
        }
        String unit = bucket == null || bucket.isBlank() ? "day" : bucket.trim().toLowerCase();
        return switch (unit) {
            case "day" -> "to_char(date_trunc('day', " + expr(column) + "), 'YYYY-MM-DD')";
            case "week" -> "to_char(date_trunc('week', " + expr(column) + "), 'YYYY-MM-DD')";
            case "month" -> "to_char(date_trunc('month', " + expr(column) + "), 'YYYY-MM')";
            case "year" -> "to_char(date_trunc('year', " + expr(column) + "), 'YYYY')";
            default -> throw new ConnectorException("Invalid bucket: '" + bucket
                    + "'. Allowed: day, week, month, year");
        };
    }

    private static String metricExpr(Metric metric, List<ColumnSpec> columns, String sheetName,
                                     List<String> outNames) {
        String func = metric == null || metric.func() == null ? "" : metric.func().trim().toLowerCase();
        if (func.equals("count")) {
            outNames.add("count");
            return "count(*)";
        }
        if (metric.column() == null || metric.column().isBlank()) {
            throw new ConnectorException("Metric '" + func + "' requires a column");
        }
        ColumnSpec column = SheetSchema.require(columns, metric.column().trim(), sheetName);
        SheetColumnType type = SheetSchema.typeOf(column);
        boolean numeric = type == SheetColumnType.NUMBER;
        switch (func) {
            case "sum", "avg" -> {
                if (!numeric) {
                    throw new ConnectorException("Metric '" + func + "' needs a numeric column, but '"
                            + column.name() + "' is " + column.type());
                }
            }
            case "min", "max" -> {
                if (!numeric && type != SheetColumnType.DATE) {
                    throw new ConnectorException("Metric '" + func + "' needs a numeric or date column, but '"
                            + column.name() + "' is " + column.type());
                }
            }
            default -> throw new ConnectorException("Invalid metric function: '" + metric.func()
                    + "'. Allowed: count, sum, avg, min, max");
        }
        outNames.add(func + "_" + column.name());
        return func + "(" + expr(column) + ")";
    }

    private void appendFilter(StringBuilder sql, List<Object> params, List<ColumnSpec> columns,
                              String sheetName, List<Condition> filter) {
        if (filter == null) {
            return;
        }
        for (Condition condition : filter) {
            if (condition == null || condition.column() == null) {
                throw new ConnectorException("Filter condition requires 'column'");
            }
            ColumnSpec column = SheetSchema.require(columns, condition.column().trim(), sheetName);
            String target = expr(column);
            String op = condition.op() == null || condition.op().isBlank()
                    ? "eq" : condition.op().trim().toLowerCase();

            switch (op) {
                case "eq" -> appendBinary(sql, params, target, "=", column, condition.value());
                case "ne" -> appendBinary(sql, params, target, "<>", column, condition.value());
                case "gt" -> appendBinary(sql, params, target, ">", column, condition.value());
                case "gte" -> appendBinary(sql, params, target, ">=", column, condition.value());
                case "lt" -> appendBinary(sql, params, target, "<", column, condition.value());
                case "lte" -> appendBinary(sql, params, target, "<=", column, condition.value());
                case "is_null" -> sql.append(" and data->>'").append(column.name()).append("' is null");
                case "not_null" -> sql.append(" and data->>'").append(column.name()).append("' is not null");
                case "contains" -> {
                    if (SheetSchema.typeOf(column) != SheetColumnType.TEXT) {
                        throw new ConnectorException("Operator 'contains' works on text columns only, but '"
                                + column.name() + "' is " + column.type());
                    }
                    params.add(requireValue(condition.value(), column.name(), "contains"));
                    sql.append(" and data->>'").append(column.name())
                            .append("' ilike '%' || ?").append(params.size()).append(" || '%'");
                }
                case "in" -> {
                    List<String> values = requireValues(condition, column.name(), 1);
                    sql.append(" and ").append(target).append(" in (");
                    for (int i = 0; i < values.size(); i++) {
                        params.add(SheetSchema.coerceParam(column, values.get(i)));
                        sql.append(i == 0 ? "?" : ", ?").append(params.size());
                    }
                    sql.append(")");
                }
                case "between" -> {
                    List<String> values = requireValues(condition, column.name(), 2);
                    params.add(SheetSchema.coerceParam(column, values.get(0)));
                    int low = params.size();
                    params.add(SheetSchema.coerceParam(column, values.get(1)));
                    sql.append(" and ").append(target).append(" between ?").append(low)
                            .append(" and ?").append(params.size());
                }
                default -> throw new ConnectorException("Invalid filter operator: '" + condition.op()
                        + "'. Allowed: eq, ne, gt, gte, lt, lte, contains, in, between, is_null, not_null");
            }
        }
    }

    private static void appendBinary(StringBuilder sql, List<Object> params, String target, String op,
                                     ColumnSpec column, String value) {
        params.add(SheetSchema.coerceParam(column, requireValue(value, column.name(), op)));
        sql.append(" and ").append(target).append(" ").append(op).append(" ?").append(params.size());
    }

    private static String requireValue(String value, String column, String op) {
        if (value == null) {
            throw new ConnectorException("Filter '" + op + "' on column '" + column + "' requires 'value'");
        }
        return value;
    }

    private static List<String> requireValues(Condition condition, String column, int expected) {
        List<String> values = condition.values();
        if (values == null || values.size() < expected
                || ("between".equals(condition.op()) && values.size() != 2)) {
            throw new ConnectorException("Filter '" + condition.op() + "' on column '" + column
                    + "' requires 'values' with " + (expected == 2 ? "exactly 2" : "at least 1") + " item(s)");
        }
        return values;
    }

    // ===== odds and ends =====

    private Query bind(String sql, List<Object> params) {
        Query query = entityManager.createNativeQuery(sql);
        for (int i = 0; i < params.size(); i++) {
            query.setParameter(i + 1, params.get(i));
        }
        return query;
    }

    private static String direction(String sortDir) {
        if (sortDir == null || sortDir.isBlank() || "asc".equalsIgnoreCase(sortDir.trim())) {
            return " asc";
        }
        if ("desc".equalsIgnoreCase(sortDir.trim())) {
            return " desc";
        }
        throw new ConnectorException("Invalid sortDir: '" + sortDir + "'. Allowed: asc, desc");
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /** JSONB from a native query arrives as a string (PGobject) — parsed with the project's shared mapper. */
    private static Map<String, Object> readJson(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        try {
            return JsonUtils.fromJsonToMap(String.valueOf(raw));
        } catch (RuntimeException e) {
            throw new ConnectorException("Failed to read row values", e);
        }
    }

    /** Numbers reach a tool's result as ordinary JSON numbers, not as a BigDecimal with a tail of zeroes. */
    private static Object jsonNumber(Object raw) {
        if (raw instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().doubleValue();
        }
        if (raw instanceof Number number) {
            return number;
        }
        return raw == null ? null : String.valueOf(raw);
    }

    private static String asKey(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }
}
