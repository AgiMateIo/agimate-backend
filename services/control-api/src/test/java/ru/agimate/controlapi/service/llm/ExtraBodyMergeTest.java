package ru.agimate.controlapi.service.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ExtraBodyMerge — deep-merge провайдер- и пер-модельного extra_body")
class ExtraBodyMergeTest {

    @Test
    @DisplayName("вложенные объекты мёржатся рекурсивно (кейс OpenRouter provider)")
    void nestedObjectsMerged() {
        Map<String, Object> provider = Map.of("provider", Map.of("data_collection", "deny"));
        Map<String, Object> model = Map.of("provider", Map.of(
                "only", List.of("moonshotai"), "require_parameters", true));

        Map<String, Object> merged = ExtraBodyMerge.merge(provider, model);

        assertEquals(Map.of("provider", Map.of(
                "data_collection", "deny",
                "only", List.of("moonshotai"),
                "require_parameters", true)), merged);
    }

    @Test
    @DisplayName("на конфликте скаляров побеждает модель; массивы заменяются целиком")
    void modelWinsAndArraysReplaced() {
        Map<String, Object> provider = Map.of(
                "reasoning_effort", "low",
                "provider", Map.of("only", List.of("a", "b")));
        Map<String, Object> model = Map.of(
                "reasoning_effort", "high",
                "provider", Map.of("only", List.of("c")));

        Map<String, Object> merged = ExtraBodyMerge.merge(provider, model);

        assertEquals("high", merged.get("reasoning_effort"));
        assertEquals(Map.of("only", List.of("c")), merged.get("provider"));
    }

    @Test
    @DisplayName("null-уровни безопасны")
    void nullSafe() {
        assertEquals(Map.of("a", 1), ExtraBodyMerge.merge(null, Map.of("a", 1)));
        assertEquals(Map.of("a", 1), ExtraBodyMerge.merge(Map.of("a", 1), null));
        assertTrue(ExtraBodyMerge.merge(null, null).isEmpty());
    }
}
