package ru.agimate.controlapi.connectors.core.dto;

import java.util.Map;

/**
 * Блок контекста от коннектора: единица содержимого для LLM-промпта агента.
 *
 * <p>{@code name} — имя XML-тега у рендерера (snake_case), {@code attrs} — его атрибуты.
 * {@code stable} — подсказка компоновщику: стабильные блоки идут раньше волатильных,
 * чтобы не ломать префикс prompt-кэша.
 *
 * @param name      имя блока (тег у рендерера), snake_case
 * @param placement куда попадает блок: системный промпт или user-ход
 * @param content   содержимое без тегов/обёртки
 * @param attrs     атрибуты тега (например версия памяти); пустая мапа, если нет
 * @param stable    меняется редко (true) или каждый ран (false)
 */
public record PromptBlock(
        String name,
        Placement placement,
        String content,
        Map<String, String> attrs,
        boolean stable
) {

    public enum Placement {SYSTEM, USER}

    public static PromptBlock system(String name, String content, Map<String, String> attrs) {
        return new PromptBlock(name, Placement.SYSTEM, content, attrs, true);
    }

    public static PromptBlock user(String name, String content) {
        return new PromptBlock(name, Placement.USER, content, Map.of(), false);
    }
}
