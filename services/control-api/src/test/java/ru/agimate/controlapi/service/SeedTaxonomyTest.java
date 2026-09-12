package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.service.seed.ContentLanguage;
import ru.agimate.controlapi.service.seed.SeedContentLocator;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Каждый системный навык и пресет объявляет раздел каталога. {@link ContentCategory#OTHER} — это
 * «ещё не разложено»: на нём же держится дозаполнение уже засеянных установок в бутстрапах, поэтому
 * системный элемент, оставшийся в OTHER, и в каталоге лежал бы в «Прочем», и никогда бы оттуда не
 * выехал. Теги не проверяются: пустой набор — законное состояние (у долговременной памяти нет ни
 * адресата, ни особенности).
 *
 * <p>Достаточно языка-первоисточника: совпадение наборов по языкам стережёт {@link SeedContentParityTest}.
 */
@DisplayName("Сид-контент — раздел каталога объявлен")
class SeedTaxonomyTest {

    static Stream<String> skills() {
        return SystemSkillBootstrap.SYSTEM_SKILL_CODES.stream();
    }

    static Stream<String> presets() {
        return SystemPresetBootstrap.SYSTEM_PRESET_CODES.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("skills")
    @DisplayName("навык не остался в OTHER")
    void skillHasCategory(String code) {
        SkillFrontmatterParser.ParsedSkill parsed = SkillFrontmatterParser.parse(
                SeedContentLocator.read(SeedContentLocator.Kind.SKILL, code, ContentLanguage.DEFAULT));

        assertNotEquals(ContentCategory.OTHER, parsed.category(), code + ": не объявлен category");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("presets")
    @DisplayName("пресет не остался в OTHER")
    void presetHasCategory(String code) {
        SystemPresetBootstrap.ParsedPreset parsed = SystemPresetBootstrap.parsePreset(
                SeedContentLocator.read(SeedContentLocator.Kind.PRESET, code, ContentLanguage.DEFAULT));

        assertNotEquals(ContentCategory.OTHER, parsed.category(), code + ": не объявлен category");
    }
}
