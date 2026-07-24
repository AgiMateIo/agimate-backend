package ru.agimate.controlapi.connectors.internal.sheets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.internal.sheets.dto.SheetDtos.ColumnSpec;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("sheets — схема и приведение значений ячеек")
class SheetSchemaTest {

    private static final ColumnSpec AMOUNT = new ColumnSpec("amount", "Сумма", "number", "₽");
    private static final ColumnSpec DATE = new ColumnSpec("date", "Дата", "date", "");
    private static final ColumnSpec PAID = new ColumnSpec("paid", "Оплачено", "bool", "");
    private static final ColumnSpec NOTE = new ColumnSpec("note", "Заметка", "text", "");

    @Nested
    @DisplayName("числа")
    class Numbers {

        @Test
        @DisplayName("принимает и число, и строку, и продиктованное «1 200,50»")
        void acceptsDictatedFormat() {
            assertEquals(new BigDecimal("1200"), SheetSchema.coerceCell(AMOUNT, 1200));
            assertEquals(new BigDecimal("1200"), SheetSchema.coerceCell(AMOUNT, "1200"));
            assertEquals(new BigDecimal("1200.50"), SheetSchema.coerceCell(AMOUNT, "1 200,50"));
        }

        @Test
        @DisplayName("мусор в числовой колонке — ошибка с именем колонки, чтобы агент понял, где ошибся")
        void rejectsGarbage() {
            ConnectorException error =
                    assertThrows(ConnectorException.class, () -> SheetSchema.coerceCell(AMOUNT, "много"));
            assertTrue(error.getMessage().contains("amount"), error.getMessage());
        }
    }

    @Nested
    @DisplayName("даты")
    class Dates {

        @Test
        @DisplayName("ISO с временем, ISO-дата и русский формат нормализуются к одному виду")
        void acceptsCommonFormats() {
            assertEquals("2026-07-24T08:30:00", SheetSchema.coerceCell(DATE, "2026-07-24T08:30"));
            assertEquals("2026-07-24T00:00:00", SheetSchema.coerceCell(DATE, "2026-07-24"));
            assertEquals("2026-07-24T00:00:00", SheetSchema.coerceCell(DATE, "24.07.2026"));
        }

        @Test
        @DisplayName("хранится всегда с секундами — иначе ::timestamp в агрегации сравнивал бы разные формы")
        void normalizesToFixedWidth() {
            assertEquals(SheetSchema.coerceCell(DATE, "24.07.2026"),
                    SheetSchema.coerceCell(DATE, "2026-07-24T00:00:00"));
        }
    }

    @Nested
    @DisplayName("прочие типы")
    class Others {

        @Test
        @DisplayName("bool понимает да/нет — пользователь диктует голосом")
        void acceptsRussianBooleans() {
            assertEquals(Boolean.TRUE, SheetSchema.coerceCell(PAID, "да"));
            assertEquals(Boolean.FALSE, SheetSchema.coerceCell(PAID, "нет"));
        }

        @Test
        @DisplayName("пустое значение — пустая ячейка (ключ в JSONB не пишется)")
        void blankIsEmptyCell() {
            assertNull(SheetSchema.coerceCell(NOTE, "  "));
            assertNull(SheetSchema.coerceCell(AMOUNT, null));
        }
    }

    @Nested
    @DisplayName("резолв колонки")
    class Resolution {

        @Test
        @DisplayName("неизвестная колонка — ошибка со списком существующих: агент чинится сам")
        void unknownColumnListsExisting() {
            ConnectorException error = assertThrows(ConnectorException.class,
                    () -> SheetSchema.require(List.of(AMOUNT, DATE), "summa", "budget"));
            assertTrue(error.getMessage().contains("amount"), error.getMessage());
            assertTrue(error.getMessage().contains("date"), error.getMessage());
            assertTrue(error.getMessage().contains("add_columns"), error.getMessage());
        }

        @Test
        @DisplayName("имя колонки обязано быть латинским slug'ом — оно уходит в SQL как ключ JSONB")
        void rejectsNonSlugNames() {
            assertThrows(ConnectorException.class,
                    () -> SheetSchema.normalize(new ColumnSpec("Сумма", "Сумма", "number", "")));
            assertThrows(ConnectorException.class,
                    () -> SheetSchema.normalize(new ColumnSpec("amount'; drop table sheets--", "x", "number", "")));
            assertEquals("amount", SheetSchema.normalize(AMOUNT).name());
        }

        @Test
        @DisplayName("неизвестный тип колонки отвергается со списком допустимых")
        void rejectsUnknownType() {
            ConnectorException error = assertThrows(ConnectorException.class,
                    () -> SheetSchema.normalize(new ColumnSpec("amount", "Сумма", "money", "")));
            assertTrue(error.getMessage().contains("number"), error.getMessage());
        }
    }
}
