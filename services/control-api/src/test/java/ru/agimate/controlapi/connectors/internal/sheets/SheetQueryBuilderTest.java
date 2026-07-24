package ru.agimate.controlapi.connectors.internal.sheets;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.ColumnSpec;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.Condition;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.GroupResult;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.Metric;
import ru.agimate.controlapi.database.entities.Sheet;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Трансляция DSL в SQL. Проверяем не «вызвался ли билдер», а сам текст запроса и bind-параметры —
 * ошибка здесь либо инъекция, либо молча неверная агрегация, и то и другое до БД не долетает.
 *
 * <p>Опорный инвариант класса: в SQL-строку попадает только имя колонки, прошедшее whitelist схемы;
 * любое значение от LLM уходит {@code ?N}-параметром уже приведённым к типу колонки.
 */
@DisplayName("sheets — DSL запроса в SQL")
class SheetQueryBuilderTest {

    private static final ColumnSpec AMOUNT = new ColumnSpec("amount", "Сумма", "number", "₽");
    private static final ColumnSpec SPENT_AT = new ColumnSpec("spent_at", "Дата", "date", "");
    private static final ColumnSpec PAID = new ColumnSpec("paid", "Оплачено", "bool", "");
    private static final ColumnSpec CATEGORY = new ColumnSpec("category", "Категория", "text", "");
    private static final List<ColumnSpec> COLUMNS = List.of(AMOUNT, SPENT_AT, PAID, CATEGORY);

    private static final UUID SHEET_ID = UUID.randomUUID();

    private final EntityManager entityManager = mock(EntityManager.class);
    private final Query query = mock(Query.class);
    private final SheetQueryBuilder builder = new SheetQueryBuilder();

    /** Последний собранный SQL и его bind-параметры по позициям (?1 → params.get(0)). */
    private String sql;
    private final List<Object> params = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(builder, "entityManager", entityManager);
        params.clear();
        when(entityManager.createNativeQuery(any(String.class))).thenAnswer(invocation -> {
            sql = invocation.getArgument(0);
            return query;
        });
        when(query.setParameter(anyInt(), any())).thenAnswer(invocation -> {
            int position = invocation.getArgument(0);
            while (params.size() < position) {
                params.add(null);
            }
            params.set(position - 1, invocation.getArgument(1));
            return query;
        });
        when(query.getResultList()).thenReturn(List.of());
    }

    private static Sheet sheet() {
        return Sheet.builder().id(SHEET_ID).name("budget").title("Бюджет")
                .columns(SheetSchema.toStorage(COLUMNS)).build();
    }

    private static Condition eq(String column, String value) {
        return new Condition(column, "eq", value, null);
    }

    private void select(List<Condition> filter) {
        builder.select(sheet(), COLUMNS, filter, null, null, null);
    }

    /** Строка результата select: {id, json}. */
    private static Object[] row(String id, String json) {
        return new Object[]{id, json};
    }

    /** Только через varargs-по-строкам: {@code List.of(oneArray)} схлопнул бы строку в отдельные ячейки. */
    private void resultRows(Object[]... rows) {
        when(query.getResultList()).thenReturn(List.of(rows));
    }

    @Nested
    @DisplayName("безопасность: имена в SQL, значения в параметрах")
    class Safety {

        @Test
        @DisplayName("значение фильтра не попадает в текст SQL — только bind-параметром")
        void valuesAreBound() {
            select(List.of(eq("category", "еда'; drop table sheet_rows--")));

            assertFalse(sql.contains("drop table"), sql);
            assertTrue(sql.contains("and data->>'category' = ?2"), sql);
            assertEquals(SHEET_ID, params.get(0));
            assertEquals("еда'; drop table sheet_rows--", params.get(1));
        }

        @Test
        @DisplayName("неизвестная колонка отвергается схемой и до SQL не доходит")
        void unknownColumnRejected() {
            ConnectorException error = assertThrows(ConnectorException.class,
                    () -> select(List.of(eq("summa", "100"))));

            assertTrue(error.getMessage().contains("amount"), error.getMessage());
            verify(entityManager, org.mockito.Mockito.never()).createNativeQuery(any(String.class));
        }

        @Test
        @DisplayName("битое имя в схеме листа не сплайсится в SQL даже для is_null (второй рубеж)")
        void malformedSchemaNameRejected() {
            ColumnSpec malformed = new ColumnSpec("amount'; drop table sheet_rows--", "x", "text", "");
            List<ColumnSpec> broken = List.of(malformed);
            Sheet sheet = Sheet.builder().id(SHEET_ID).name("budget").title("Бюджет")
                    .columns(SheetSchema.toStorage(broken)).build();

            for (String op : List.of("eq", "is_null", "contains")) {
                Condition condition = new Condition(malformed.name(), op, "x", null);
                ConnectorException error = assertThrows(ConnectorException.class,
                        () -> builder.select(sheet, broken, List.of(condition), null, null, null), op);
                assertTrue(error.getMessage().contains("Malformed column name"), op + ": " + error.getMessage());
            }
        }

        @Test
        @DisplayName("sortDir и bucket — закрытые списки, произвольная строка отвергается")
        void enumsAreClosed() {
            assertThrows(ConnectorException.class,
                    () -> builder.select(sheet(), COLUMNS, null, "amount", "asc; drop table sheet_rows--", null));
            assertThrows(ConnectorException.class, () -> builder.aggregate(sheet(), COLUMNS, "spent_at",
                    "day'--", List.of(new Metric(null, "count")), null));
        }
    }

    @Nested
    @DisplayName("select")
    class Select {

        @Test
        @DisplayName("без фильтра и сортировки — порядок вставки, лимит по умолчанию")
        void defaults() {
            select(null);

            assertEquals("select id, data from sheet_rows where sheet_id = ?1"
                    + " order by created_at asc limit " + (SheetQueryBuilder.DEFAULT_LIMIT + 1), sql);
            assertEquals(List.of(SHEET_ID), params);
        }

        @Test
        @DisplayName("сортировка кастует колонку по объявленному типу и кладёт NULL в конец")
        void sortCastsByType() {
            builder.select(sheet(), COLUMNS, null, "spent_at", "desc", null);

            assertTrue(sql.contains(" order by (data->>'spent_at')::timestamp desc nulls last"), sql);
        }

        @Test
        @DisplayName("лимит зажимается в MAX_LIMIT, непозитивный — в дефолт")
        void limitIsClamped() {
            builder.select(sheet(), COLUMNS, null, null, null, 10_000);
            assertTrue(sql.endsWith("limit " + (SheetQueryBuilder.MAX_LIMIT + 1)), sql);

            builder.select(sheet(), COLUMNS, null, null, null, 0);
            assertTrue(sql.endsWith("limit " + (SheetQueryBuilder.DEFAULT_LIMIT + 1)), sql);
        }

        @Test
        @DisplayName("берётся на строку больше лимита: лишняя строка не отдаётся, но взводит truncated")
        void truncatedDetectedWithoutCount() {
            resultRows(row("r1", "{\"amount\":10}"), row("r2", "{\"amount\":20}"), row("r3", "{\"amount\":30}"));

            SheetQueryBuilder.Selection selection = builder.select(sheet(), COLUMNS, null, null, null, 2);

            assertTrue(selection.truncated());
            assertEquals(List.of("r1", "r2"), selection.rows().stream().map(r -> r.id()).toList());
            assertEquals(Map.of("amount", 10), selection.rows().get(0).values());
        }

        @Test
        @DisplayName("строк ровно по лимиту — truncated=false")
        void exactlyAtLimitIsNotTruncated() {
            resultRows(row("r1", "{}"), row("r2", "{}"));

            assertFalse(builder.select(sheet(), COLUMNS, null, null, null, 2).truncated());
        }

        /**
         * Фиксируем фактическое поведение, а не задуманное: catch → ConnectorException в
         * {@code readJson} недостижим, потому что {@code JsonUtils.fromJsonToMap} гасит ошибку
         * разбора сам и возвращает пустую карту. Строка приезжает агенту без значений и молча.
         */
        @Test
        @DisplayName("нечитаемый JSONB строки не роняет выборку — строка приходит пустой")
        void brokenJsonDegradesToEmptyRow() {
            resultRows(row("r1", "{не json"));

            SheetQueryBuilder.Selection selection = builder.select(sheet(), COLUMNS, null, null, null, null);

            assertEquals(1, selection.rows().size());
            assertEquals(Map.of(), selection.rows().get(0).values());
        }
    }

    @Nested
    @DisplayName("операторы фильтра")
    class Filters {

        @Test
        @DisplayName("бинарные операторы кастуют колонку и приводят значение к типу колонки")
        void binaryOperators() {
            select(List.of(new Condition("amount", "gte", "1 200,50", null)));

            assertTrue(sql.contains("and (data->>'amount')::numeric >= ?2"), sql);
            assertEquals(new BigDecimal("1200.50"), params.get(1));
        }

        @Test
        @DisplayName("op по умолчанию — eq; регистр оператора не важен")
        void operatorDefaultsAndCase() {
            select(List.of(new Condition("category", null, "еда", null)));
            assertTrue(sql.contains("and data->>'category' = ?2"), sql);

            select(List.of(new Condition("category", "  NE  ", "еда", null)));
            assertTrue(sql.contains("and data->>'category' <> ?2"), sql);
        }

        @Test
        @DisplayName("дата и bool уходят параметром типизированными, а не строкой")
        void typedParams() {
            select(List.of(new Condition("spent_at", "lt", "24.07.2026", null),
                    new Condition("paid", "eq", "да", null)));

            assertTrue(sql.contains("(data->>'spent_at')::timestamp < ?2"), sql);
            assertTrue(sql.contains("(data->>'paid')::boolean = ?3"), sql);
            assertEquals(LocalDateTime.of(2026, 7, 24, 0, 0), params.get(1));
            assertEquals(Boolean.TRUE, params.get(2));
        }

        @Test
        @DisplayName("contains — ilike с обрамлением процентами в параметре, только по text-колонке")
        void containsIsTextOnly() {
            select(List.of(new Condition("category", "contains", "прод", null)));
            assertTrue(sql.contains("and data->>'category' ilike '%' || ?2 || '%'"), sql);
            assertEquals("прод", params.get(1));

            ConnectorException error = assertThrows(ConnectorException.class,
                    () -> select(List.of(new Condition("amount", "contains", "1", null))));
            assertTrue(error.getMessage().contains("text columns only"), error.getMessage());
        }

        @Test
        @DisplayName("in — по параметру на значение, каждое приведено к типу колонки")
        void inBindsEveryValue() {
            select(List.of(new Condition("amount", "in", null, List.of("10", "20", "30"))));

            assertTrue(sql.contains("and (data->>'amount')::numeric in (?2, ?3, ?4)"), sql);
            assertEquals(List.of(SHEET_ID, new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30")),
                    params);
        }

        @Test
        @DisplayName("between — ровно две границы, обе параметрами")
        void betweenBindsBothBounds() {
            select(List.of(new Condition("spent_at", "between", null,
                    List.of("2026-07-01", "2026-07-31"))));

            assertTrue(sql.contains("and (data->>'spent_at')::timestamp between ?2 and ?3"), sql);
            assertEquals(LocalDateTime.of(2026, 7, 1, 0, 0), params.get(1));
            assertEquals(LocalDateTime.of(2026, 7, 31, 0, 0), params.get(2));
        }

        @Test
        @DisplayName("is_null/not_null проверяют отсутствие ключа в JSONB и не берут параметров")
        void nullChecksTakeNoParams() {
            select(List.of(new Condition("amount", "is_null", null, null),
                    new Condition("category", "not_null", null, null)));

            assertTrue(sql.contains("and data->>'amount' is null"), sql);
            assertTrue(sql.contains("and data->>'category' is not null"), sql);
            assertEquals(List.of(SHEET_ID), params);
        }

        @Test
        @DisplayName("несколько условий соединяются по AND")
        void conditionsAreConjunctive() {
            select(List.of(eq("category", "еда"), new Condition("amount", "gt", "100", null)));

            assertEquals("select id, data from sheet_rows where sheet_id = ?1"
                    + " and data->>'category' = ?2"
                    + " and (data->>'amount')::numeric > ?3"
                    + " order by created_at asc limit " + (SheetQueryBuilder.DEFAULT_LIMIT + 1), sql);
        }

        @Test
        @DisplayName("ошибки условия объясняют агенту, что именно чинить")
        void errorsAreActionable() {
            assertTrue(assertThrows(ConnectorException.class,
                    () -> select(List.of(new Condition("amount", "like", "1", null))))
                    .getMessage().contains("Allowed: eq, ne"));
            assertTrue(assertThrows(ConnectorException.class,
                    () -> select(List.of(new Condition("amount", "eq", null, null))))
                    .getMessage().contains("requires 'value'"));
            assertTrue(assertThrows(ConnectorException.class,
                    () -> select(List.of(new Condition("amount", "in", null, List.of()))))
                    .getMessage().contains("at least 1"));
            assertTrue(assertThrows(ConnectorException.class,
                    () -> select(List.of(new Condition("spent_at", "between", null, List.of("2026-07-01")))))
                    .getMessage().contains("exactly 2"));
            assertTrue(assertThrows(ConnectorException.class,
                    () -> select(List.of(new Condition(null, "eq", "1", null))))
                    .getMessage().contains("requires 'column'"));
        }
    }

    @Nested
    @DisplayName("aggregate")
    class Aggregate {

        @Test
        @DisplayName("без groupBy — одна строка метрик, без group by и без капа групп")
        void metricsWithoutGrouping() {
            resultRows(new Object[]{new BigDecimal("1200.500"), 7L});

            List<GroupResult> groups = builder.aggregate(sheet(), COLUMNS, null, null,
                    List.of(new Metric("amount", "sum"), new Metric(null, "count")), null);

            assertEquals("select sum((data->>'amount')::numeric), count(*)"
                    + " from sheet_rows where sheet_id = ?1", sql);
            assertEquals(1, groups.size());
            assertNull(groups.get(0).key());
            assertEquals(Map.of("sum_amount", 1200.5, "count", 7L), groups.get(0).metrics());
        }

        @Test
        @DisplayName("одна метрика без groupBy: скалярный результат не Object[] — не падаем")
        void scalarResultRow() {
            when(query.getResultList()).thenReturn(List.of(new BigDecimal("42")));

            List<GroupResult> groups = builder.aggregate(sheet(), COLUMNS, null, null,
                    List.of(new Metric("amount", "sum")), null);

            assertEquals(Map.of("sum_amount", 42.0), groups.get(0).metrics());
        }

        @Test
        @DisplayName("groupBy по не-дате — приведение ключа к тексту, порядок и кап групп")
        void groupByText() {
            resultRows(new Object[]{"еда", 3L});

            List<GroupResult> groups = builder.aggregate(sheet(), COLUMNS, "category", null,
                    List.of(new Metric(null, "count")), null);

            assertEquals("select (data->>'category')::text as grp, count(*)"
                    + " from sheet_rows where sheet_id = ?1"
                    + " group by 1 order by 1 limit " + SheetQueryBuilder.MAX_GROUPS, sql);
            assertEquals("еда", groups.get(0).key());
            assertEquals(Map.of("count", 3L), groups.get(0).metrics());
        }

        @Test
        @DisplayName("groupBy по дате — bucket'ы дают сортируемый строковый ключ, по умолчанию day")
        void dateBuckets() {
            builder.aggregate(sheet(), COLUMNS, "spent_at", null, List.of(new Metric(null, "count")), null);
            assertTrue(sql.startsWith("select to_char(date_trunc('day', (data->>'spent_at')::timestamp),"
                    + " 'YYYY-MM-DD') as grp"), sql);

            builder.aggregate(sheet(), COLUMNS, "spent_at", " Month ", List.of(new Metric(null, "count")), null);
            assertTrue(sql.contains("date_trunc('month'"), sql);
            assertTrue(sql.contains("'YYYY-MM')"), sql);
        }

        @Test
        @DisplayName("фильтр в агрегации — те же bind-параметры после sheet_id")
        void filterApplies() {
            builder.aggregate(sheet(), COLUMNS, "category", null, List.of(new Metric(null, "count")),
                    List.of(new Condition("amount", "gt", "100", null)));

            assertTrue(sql.contains("where sheet_id = ?1 and (data->>'amount')::numeric > ?2 group by 1"), sql);
            assertEquals(new BigDecimal("100"), params.get(1));
        }

        @Test
        @DisplayName("BigDecimal в метрике отдаётся JSON-числом без хвоста нулей")
        void numbersAreJsonFriendly() {
            resultRows(new Object[]{new BigDecimal("10.00")});

            List<GroupResult> groups = builder.aggregate(sheet(), COLUMNS, null, null,
                    List.of(new Metric("amount", "avg")), null);

            assertEquals(10.0, groups.get(0).metrics().get("avg_amount"));
        }

        @Test
        @DisplayName("метрика проверяется по объявленному типу колонки — sum по тексту не доезжает до БД")
        void metricTypeIsChecked() {
            ConnectorException error = assertThrows(ConnectorException.class,
                    () -> builder.aggregate(sheet(), COLUMNS, null, null,
                            List.of(new Metric("category", "sum")), null));
            assertTrue(error.getMessage().contains("numeric column"), error.getMessage());

            // min/max осмысленны и на датах — «первая и последняя трата»
            builder.aggregate(sheet(), COLUMNS, null, null, List.of(new Metric("spent_at", "min")), null);
            assertTrue(sql.contains("min((data->>'spent_at')::timestamp)"), sql);

            assertTrue(assertThrows(ConnectorException.class,
                    () -> builder.aggregate(sheet(), COLUMNS, null, null,
                            List.of(new Metric("paid", "max")), null))
                    .getMessage().contains("numeric or date"), "max по bool");
        }

        @Test
        @DisplayName("count не требует колонки, остальные функции — требуют")
        void countNeedsNoColumn() {
            builder.aggregate(sheet(), COLUMNS, null, null, List.of(new Metric(null, "count")), null);
            assertTrue(sql.contains("count(*)"), sql);

            assertTrue(assertThrows(ConnectorException.class,
                    () -> builder.aggregate(sheet(), COLUMNS, null, null,
                            List.of(new Metric(null, "sum")), null))
                    .getMessage().contains("requires a column"));
        }

        @Test
        @DisplayName("пустые метрики и неизвестная функция — подсказка со списком допустимых")
        void metricErrorsAreActionable() {
            assertTrue(assertThrows(ConnectorException.class,
                    () -> builder.aggregate(sheet(), COLUMNS, null, null, List.of(), null))
                    .getMessage().contains("count, sum, avg, min, max"));
            assertTrue(assertThrows(ConnectorException.class,
                    () -> builder.aggregate(sheet(), COLUMNS, null, null, null, null))
                    .getMessage().contains("At least one metric"));
            assertTrue(assertThrows(ConnectorException.class,
                    () -> builder.aggregate(sheet(), COLUMNS, null, null,
                            List.of(new Metric("amount", "median")), null))
                    .getMessage().contains("Invalid metric function"));
        }
    }
}
