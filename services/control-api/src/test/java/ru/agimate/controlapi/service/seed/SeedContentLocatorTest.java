package ru.agimate.controlapi.service.seed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.config.ContentProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SeedContentLocator")
class SeedContentLocatorTest {

    private ContentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ContentProperties();
    }

    private SeedContentLocator locator(ContentLanguage language) {
        properties.setLanguage(language);
        return new SeedContentLocator(properties);
    }

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
    @DisplayName("Чтение по языку инсталляции")
    class Read {

        @Test
        @DisplayName("каждый язык отдаёт свой файл")
        void readsConfiguredLanguage() {
            String ru = locator(ContentLanguage.RU).read(SeedContentLocator.Kind.SKILL, "time");
            String en = locator(ContentLanguage.EN).read(SeedContentLocator.Kind.SKILL, "time");

            assertNotEquals(ru, en, "оба языка вернули один текст — язык не подставляется");
        }

        /**
         * Промах уходит на {@link ContentLanguage#DEFAULT}, а не роняет сидинг: иначе один
         * непереведённый скилл выкинул бы из каталога все ссылающиеся на него пресеты. Проверяем
         * по пути в сообщении — фолбэк случился до отказа.
         */
        @Test
        @DisplayName("нет файла для языка — фолбэк на язык-первоисточник")
        void fallsBackToDefault() {
            SeedContentLocator locator = locator(ContentLanguage.EN);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> locator.read(SeedContentLocator.Kind.SKILL, "no-such-skill"));

            assertTrue(e.getMessage().contains("/" + ContentLanguage.DEFAULT.dir() + "/"),
                    "ожидался фолбэк на " + ContentLanguage.DEFAULT + ", а путь в ошибке: " + e.getMessage());
        }
    }
}
