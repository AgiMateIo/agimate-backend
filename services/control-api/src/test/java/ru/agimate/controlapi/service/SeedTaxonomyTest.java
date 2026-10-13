package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.service.seed.SeedContentLocator;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Каждый системный навык и пресет объявляет раздел каталога. {@link ContentCategory#OTHER} — это
 * «ещё не разложено»: на нём же держится дозаполнение уже засеянных установок в бутстрапах, поэтому
 * системный элемент, оставшийся в OTHER, и в каталоге лежал бы в «Прочем», и никогда бы оттуда не
 * выехал. Теги не проверяются: пустой набор — законное состояние (у долговременной памяти нет ни
 * адресата, ни особенности).
 *
 * <p>Проверяется каждый язык: каталоги независимы, и раздел у языковых версий одного кода может различаться.
 */
@DisplayName("Сид-контент — раздел каталога объявлен")
class SeedTaxonomyTest {

    static Stream<Arguments> skills() {
        return existing(SeedContentLocator.Kind.SKILL, SystemSkillBootstrap.SYSTEM_SKILL_CODES);
    }

    static Stream<Arguments> presets() {
        return existing(SeedContentLocator.Kind.PRESET, SystemPresetBootstrap.SYSTEM_PRESET_CODES);
    }

    private static Stream<Arguments> existing(SeedContentLocator.Kind kind, List<String> codes) {
        return Stream.of(ContentLanguage.values()).flatMap(language -> codes.stream()
                .filter(code -> SeedContentLocator.exists(kind, code, language))
                .map(code -> Arguments.of(language, code)));
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("skills")
    @DisplayName("навык не остался в OTHER")
    void skillHasCategory(ContentLanguage language, String code) {
        SkillFrontmatterParser.ParsedSkill parsed = SkillFrontmatterParser.parse(
                SeedContentLocator.read(SeedContentLocator.Kind.SKILL, code, language));

        assertNotEquals(ContentCategory.OTHER, parsed.category(), language + "/" + code + ": не объявлен category");
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("presets")
    @DisplayName("пресет не остался в OTHER")
    void presetHasCategory(ContentLanguage language, String code) {
        SystemPresetBootstrap.ParsedPreset parsed = SystemPresetBootstrap.parsePreset(
                SeedContentLocator.read(SeedContentLocator.Kind.PRESET, code, language));

        assertNotEquals(ContentCategory.OTHER, parsed.category(), language + "/" + code + ": не объявлен category");
    }
}
