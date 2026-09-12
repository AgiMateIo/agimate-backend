package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.agimate.controlapi.service.seed.ContentLanguage;
import ru.agimate.controlapi.service.seed.SeedContentLocator;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.util.stream.Stream;
import java.util.List;
import ru.agimate.controlapi.database.model.ConnectorRequirement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Паритет языковых наборов сид-контента. Переводу подлежат только {@code title}, {@code description},
 * тело и подпись требования ({@code connectors[].title} — текст мастера подключения); остальной
 * frontmatter — машинные ключи, и переведённый ключ ломает связь молча:
 * {@code skills:} — привязку пресета к скилу, {@code connectors:} — привязку скила к коннектору,
 * {@code sortOrder} — порядок галереи мастера, {@code category}/{@code tags} — раздел каталога и
 * фильтры.
 *
 * <p>В БД лежит один языковой набор ({@code app.content.language} выбирается для свежей
 * инсталляции), поэтому расхождение не всплыло бы больше нигде: каждый язык по отдельности
 * самосогласован, а сломается только та инсталляция, где выбран второй язык.
 */
@DisplayName("Сид-контент — паритет языковых наборов")
class SeedContentParityTest {

    private static final ContentLanguage BASE = ContentLanguage.DEFAULT;

    /** Языки, кроме первоисточника: с ним и сравниваем. */
    private static Stream<ContentLanguage> translations() {
        return Stream.of(ContentLanguage.values()).filter(language -> language != BASE);
    }

    static Stream<Arguments> skills() {
        return translations().flatMap(language ->
                SystemSkillBootstrap.SYSTEM_SKILL_CODES.stream().map(code -> Arguments.of(language, code)));
    }

    static Stream<Arguments> presets() {
        return translations().flatMap(language ->
                SystemPresetBootstrap.SYSTEM_PRESET_CODES.stream().map(code -> Arguments.of(language, code)));
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("skills")
    @DisplayName("скилл: слаги совпадают с первоисточником, текст — нет")
    void skillSlugsMatchTextDiffers(ContentLanguage language, String code) {
        SkillFrontmatterParser.ParsedSkill base = parseSkill(code, BASE);
        SkillFrontmatterParser.ParsedSkill translated = parseSkill(code, language);

        assertEquals(base.name(), translated.name(), "name");
        assertEquals(withoutTitles(base.connectors()), withoutTitles(translated.connectors()),
                "connectors (порядок тоже; подпись требования переводится и не сравнивается)");
        assertEquals(base.category(), translated.category(), "category — раздел каталога");
        assertEquals(base.tags(), translated.tags(), "tags (порядок тоже)");
        assertNotEquals(base.description(), translated.description(),
                "description совпал с " + BASE + " — файл скопирован, а не переведён");
        assertNotEquals(base.body(), translated.body(),
                "тело совпало с " + BASE + " — файл скопирован, а не переведён");
    }

    private static List<ConnectorRequirement> withoutTitles(List<ConnectorRequirement> requirements) {
        return requirements.stream()
                .map(r -> new ConnectorRequirement(r.code(), r.key(), null, r.params(), r.tools(), r.triggers()))
                .toList();
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("presets")
    @DisplayName("пресет: слаги и порядок совпадают с первоисточником, текст — нет")
    void presetSlugsMatchTextDiffers(ContentLanguage language, String code) {
        SystemPresetBootstrap.ParsedPreset base = parsePreset(code, BASE);
        SystemPresetBootstrap.ParsedPreset translated = parsePreset(code, language);

        assertEquals(base.name(), translated.name(), "name");
        assertEquals(base.skillNames(), translated.skillNames(), "skills (порядок тоже)");
        assertEquals(base.sortOrder(), translated.sortOrder(), "sortOrder — порядок галереи мастера");
        assertEquals(base.agentType(), translated.agentType(), "agentType — какого агента создаёт пресет");
        assertEquals(base.category(), translated.category(), "category — раздел галереи");
        assertEquals(base.tags(), translated.tags(), "tags (порядок тоже)");
        assertNotEquals(base.instructions(), translated.instructions(),
                "instructions совпали с " + BASE + " — файл скопирован, а не переведён");
    }

    private static SkillFrontmatterParser.ParsedSkill parseSkill(String code, ContentLanguage language) {
        return SkillFrontmatterParser.parse(SeedContentLocator.read(SeedContentLocator.Kind.SKILL, code, language));
    }

    private static SystemPresetBootstrap.ParsedPreset parsePreset(String code, ContentLanguage language) {
        return SystemPresetBootstrap.parsePreset(SeedContentLocator.read(SeedContentLocator.Kind.PRESET, code, language));
    }
}
