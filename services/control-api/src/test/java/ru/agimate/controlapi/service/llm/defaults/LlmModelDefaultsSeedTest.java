package ru.agimate.controlapi.service.llm.defaults;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.service.llm.catalog.LlmCatalogSeed;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Снапшот в поставке. Файл уезжает в jar и правится только у нас, поэтому его целостность —
 * вопрос сборки, а не рантайма: битую строку в двух с половиной тысячах должен ловить тест,
 * а не пользователь, у которого молча пропала модель.
 */
@DisplayName("LlmModelDefaultsSeed — снапшот возможностей моделей")
class LlmModelDefaultsSeedTest {

    private static final List<LlmModelDefaultsSeedEntry> ENTRIES = LlmModelDefaultsSeed.load();

    @Test
    @DisplayName("снапшот непустой и id моделей уникальны")
    void modelsAreUnique() {
        assertFalse(ENTRIES.isEmpty(), "снапшот пуст — фолбэку нечем заполнять пробелы дискавери");

        Set<String> seen = new HashSet<>();
        for (LlmModelDefaultsSeedEntry entry : ENTRIES) {
            assertTrue(seen.add(entry.model()), "модель '" + entry.model() + "' встречается дважды: "
                    + "upsert идёт по id, вторая запись молча затрёт первую");
        }
    }

    @Test
    @DisplayName("id модели есть у каждой записи — остальное может отсутствовать")
    void modelIdIsAlwaysPresent() {
        for (LlmModelDefaultsSeedEntry entry : ENTRIES) {
            assertNotNull(entry.model());
            assertFalse(entry.model().isBlank());
        }
    }

    @Test
    @DisplayName("модальности не пустые списки: «не объявлено» — это null, а не []")
    void modalitiesAreNullOrNonEmpty() {
        // Разница смысловая: пустой список прочитается как «модель ничего не принимает», тогда как
        // источник всего лишь не сообщил ничего. MediaInferenceService на этом различии и стоит.
        for (LlmModelDefaultsSeedEntry entry : ENTRIES) {
            assertNotEmptyOrNull(entry.inputModalities(), entry.model(), "inputModalities");
            assertNotEmptyOrNull(entry.outputModalities(), entry.model(), "outputModalities");
            assertNotEmptyOrNull(entry.supportedParameters(), entry.model(), "supportedParameters");
        }
    }

    @Test
    @DisplayName("контекст и потолок ответа положительные там, где заданы")
    void numbersArePositive() {
        for (LlmModelDefaultsSeedEntry entry : ENTRIES) {
            if (entry.contextWindow() != null) {
                assertTrue(entry.contextWindow() > 0, entry.model() + ": contextWindow не положителен");
            }
            if (entry.maxOutputTokens() != null) {
                assertTrue(entry.maxOutputTokens() > 0, entry.model() + ": maxOutputTokens не положителен");
            }
        }
    }

    @Test
    @DisplayName("ни алиасов, ни batch-вариантов")
    void holdsNeitherAliasesNorBatchVariants() {
        for (LlmModelDefaultsSeedEntry entry : ENTRIES) {
            // Алиас описывал бы возможности той модели, которой он означал в день снятия снапшота.
            assertFalse(entry.model().startsWith("~"), entry.model() + " — плавающий алиас");
            // :batch — продуктовый вариант OpenRouter поверх уже перечисленной модели. Фолбэк нужен
            // там, где дискавери отдаёт голые id (прямые OpenAI и Anthropic), а такой id они не
            // выдают вовсе — строка не нашлась бы никогда.
            assertFalse(entry.model().endsWith(":batch"), entry.model() + " — batch-вариант");
        }
    }

    @Test
    @DisplayName("каждая модель из рекомендаций OpenRouter описана в снапшоте")
    void catalogRecommendationsAreCovered() {
        // Снапшот снят с листинга OpenRouter, поэтому сверяем только запись openrouter: у Polza своё
        // пространство имён, её id в этом снапшоте отсутствуют по построению.
        // Рекомендация без строки здесь приедет к пользователю безликой — без модальностей и
        // контекста, — а MediaInferenceService ещё и отвергнет её до HTTP: пустые модальности он
        // читает как «не объявлено», но именно от них зависит выбор модели под медиа-тул.
        Set<String> known = ENTRIES.stream()
                .map(LlmModelDefaultsSeedEntry::model)
                .collect(Collectors.toUnmodifiableSet());

        LlmCatalogSeed.load().stream()
                .filter(entry -> "openrouter".equals(entry.code()))
                .findFirst()
                .orElseThrow()
                .purposePriority()
                .forEach((purpose, models) -> models.forEach(model ->
                        assertTrue(known.contains(model),
                                "openrouter." + purpose + ": '" + model + "' нет в снапшоте возможностей")));
    }

    @Test
    @DisplayName("битый путь роняет загрузку, а не отдаёт половину снапшота")
    void missingFileFails() {
        assertThrows(IllegalStateException.class, () -> LlmModelDefaultsSeed.load("seed/no-such-file.yaml"));
    }

    private static void assertNotEmptyOrNull(List<String> values, String model, String field) {
        if (values == null) {
            return;
        }
        assertFalse(values.isEmpty(), model + ": " + field + " — пустой список вместо null");
        assertEquals(values.size(), Set.copyOf(values).size(), model + ": " + field + " с дублями");
    }
}
