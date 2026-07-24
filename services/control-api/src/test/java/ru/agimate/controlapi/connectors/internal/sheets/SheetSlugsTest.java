package ru.agimate.controlapi.connectors.internal.sheets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("sheets — заголовок импортируемой таблицы в машинное имя колонки")
class SheetSlugsTest {

    @Test
    @DisplayName("русский заголовок транслитерируется в латинский snake_case")
    void transliteratesRussian() {
        assertEquals("kategoriya_rashoda", SheetSlugs.slug("Категория расхода"));
        assertEquals("summa", SheetSlugs.slug("Сумма, ₽"));
        assertEquals("data_platezha", SheetSlugs.slug("Дата платежа"));
    }

    @Test
    @DisplayName("имя обязано начинаться с буквы — заголовок из цифр получает префикс")
    void ensuresLeadingLetter() {
        String slug = SheetSlugs.slug("2026 год");
        assertTrue(Character.isLetter(slug.charAt(0)), slug);
        assertTrue(SheetSchema.NAME.matcher(slug).matches(), slug);
    }

    @Test
    @DisplayName("любой результат подходит под ограничение имени колонки")
    void alwaysMatchesNamePattern() {
        List.of("Итого!!!", "  price / шт  ", "A", "Долгий-предолгий заголовок из таблицы бухгалтера 2026")
                .forEach(header -> {
                    String slug = SheetSlugs.slug(header);
                    assertTrue(SheetSchema.NAME.matcher(slug).matches(), header + " → " + slug);
                });
    }

    @Test
    @DisplayName("одинаковые заголовки разводятся суффиксом, а не схлопываются в одну колонку")
    void deduplicates() {
        List<String> taken = List.of("summa");
        assertEquals("summa_2", SheetSlugs.unique("Сумма", taken, "column_2"));
    }

    @Test
    @DisplayName("пустой заголовок заменяется запасным именем")
    void fallsBackOnEmptyHeader() {
        assertEquals("column_3", SheetSlugs.unique("   ", List.of(), "column_3"));
    }
}
