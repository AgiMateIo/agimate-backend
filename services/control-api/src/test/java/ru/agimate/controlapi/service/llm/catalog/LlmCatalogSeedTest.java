package ru.agimate.controlapi.service.llm.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверки самого сида, а не кода, который его читает: файл уезжает в jar, правится только у нас, и
 * сломать его можно лишь коммитом — значит ловить надо на сборке. Здесь же живут семантические
 * правила, которых нет в {@link LlmCatalogSeed}: он про разбор, тест про смысл.
 */
@DisplayName("LlmCatalogSeed — каталог провайдеров в поставке")
class LlmCatalogSeedTest {

    private static final List<LlmCatalogSeedEntry> ENTRIES = LlmCatalogSeed.load();

    @Test
    @DisplayName("сид непустой, коды и названия уникальны")
    void codesAreUnique() {
        assertFalse(ENTRIES.isEmpty(), "каталог пуст — форме нечего предлагать");

        Set<String> codes = new HashSet<>();
        for (LlmCatalogSeedEntry entry : ENTRIES) {
            assertTrue(codes.add(entry.code()), "код '" + entry.code() + "' встречается дважды: "
                    + "upsert идёт по коду, вторая запись молча затрёт первую");
        }
        assertEquals(ENTRIES.size(), ENTRIES.stream().map(LlmCatalogSeedEntry::name).distinct().count(),
                "названия повторяются — в списке их не различить");
    }

    @Test
    @DisplayName("у OPENAI_COMPATIBLE задан base_url")
    void openAiCompatibleHasBaseUrl() {
        for (LlmCatalogSeedEntry entry : ENTRIES) {
            if (entry.providerType() != LlmProviderType.OPENAI_COMPATIBLE) {
                continue;
            }
            // Иначе предзаполненная форма даёт 400 от LlmProviderService.validateBaseUrl — подсказка
            // приводит ровно туда, откуда пользователь и не смог бы выбраться сам.
            assertNotNull(entry.baseUrl(), entry.code() + ": OPENAI_COMPATIBLE без base_url");
        }
    }

    @Test
    @DisplayName("тип провайдера запускается в агентском цикле")
    void providerTypeIsRunnable() {
        for (LlmCatalogSeedEntry entry : ENTRIES) {
            // agent-worker ModelFactory принимает только OpenAI-диалект; ANTHROPIC и GEMINI дискавери
            // поддерживает, а агент на них падает — в каталоге им нечего делать, пока это так.
            assertTrue(entry.providerType() == LlmProviderType.OPENAI
                            || entry.providerType() == LlmProviderType.OPENAI_COMPATIBLE,
                    entry.code() + ": тип " + entry.providerType() + " не запускается в агентском цикле");
        }
    }

    @Test
    @DisplayName("purpose_priority — списки без пустых значений и дублей")
    void modelsAreCleanLists() {
        for (LlmCatalogSeedEntry entry : ENTRIES) {
            if (entry.purposePriority() == null) {
                continue;
            }
            entry.purposePriority().forEach((purpose, models) -> {
                assertFalse(models.isEmpty(), entry.code() + "." + purpose + ": пустой список значит "
                        + "«выключено намеренно» — в рекомендации это бессмысленно, ключ надо убрать");
                Set<String> seen = new HashSet<>();
                for (String model : models) {
                    assertFalse(model.isBlank(), entry.code() + "." + purpose + ": пустой id модели");
                    assertTrue(seen.add(model), entry.code() + "." + purpose + ": '" + model + "' дважды");
                }
            });
        }
    }

    @Test
    @DisplayName("плавающие алиасы OpenRouter доехали строками, а не null")
    void aliasesSurviveYamlParsing() {
        // Голый ~ в YAML — это null. Алиасы вида ~anthropic/claude-sonnet-latest обязаны быть в
        // кавычках, иначе список молча превратится в null'ы — самый тихий способ сломать сид.
        List<String> chat = entry("openrouter").purposePriority().get(LlmPurpose.CHAT);

        assertTrue(chat.stream().anyMatch(model -> model.startsWith("~")),
                "в CHAT не осталось ни одного алиаса — проверять стало нечего");
    }

    @Test
    @DisplayName("описание есть у каждой записи — это фолбэк для перевода")
    void descriptionsArePresent() {
        for (LlmCatalogSeedEntry entry : ENTRIES) {
            assertNotNull(entry.description(), entry.code() + ": нет description");
        }
    }

    @Test
    @DisplayName("битый путь роняет загрузку, а не отдаёт половину каталога")
    void missingFileFails() {
        assertThrows(IllegalStateException.class, () -> LlmCatalogSeed.load("seed/no-such-file.yaml"));
    }

    private static LlmCatalogSeedEntry entry(String code) {
        return ENTRIES.stream()
                .filter(entry -> code.equals(entry.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("в каталоге нет записи '" + code + "'"));
    }
}
