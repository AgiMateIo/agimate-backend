package ru.agimate.controlapi.service.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.database.enums.ContentLanguage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SeedContentLocator")
class SeedContentLocatorTest {

    @Nested
    @DisplayName("Раскладка")
    class Layout {

        @Test
        @DisplayName("путь собирается из вида, языка и кода")
        void buildsPath() {
            assertEquals("seed/presets/ru/visual/PRESET.md",
                    SeedContentLocator.path(SeedContentLocator.Kind.PRESET, "visual", ContentLanguage.RU));
            assertEquals("seed/skills/en/time/SKILL.md",
                    SeedContentLocator.path(SeedContentLocator.Kind.SKILL, "time", ContentLanguage.EN));
        }

        @Test
        @DisplayName("exists различает существующий код и выдуманный")
        void detectsMissing() {
            assertTrue(SeedContentLocator.exists(SeedContentLocator.Kind.SKILL, "time", ContentLanguage.RU));
            assertFalse(SeedContentLocator.exists(SeedContentLocator.Kind.SKILL, "no-such-skill", ContentLanguage.RU));
        }
    }

    @Nested
    @DisplayName("Чтение")
    class Read {

        @Test
        @DisplayName("каждый язык отдаёт свой файл")
        void readsRequestedLanguage() {
            String ru = SeedContentLocator.read(SeedContentLocator.Kind.SKILL, "time", ContentLanguage.RU);
            String en = SeedContentLocator.read(SeedContentLocator.Kind.SKILL, "time", ContentLanguage.EN);

            assertNotEquals(ru, en, "оба языка вернули один текст — язык не подставляется");
        }

        /** Каталоги языков независимы: кода нет в папке языка — его нет в каталоге, подмены другим языком нет. */
        @Test
        @DisplayName("нет файла для языка — отказ, а не подмена другим языком")
        void noFallbackToAnotherLanguage() {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> SeedContentLocator.read(SeedContentLocator.Kind.SKILL, "no-such-skill", ContentLanguage.RU));

            assertTrue(e.getMessage().contains("/ru/"), e.getMessage());
        }
    }
}
