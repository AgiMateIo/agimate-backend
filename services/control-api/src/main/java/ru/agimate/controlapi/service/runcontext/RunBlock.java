package ru.agimate.controlapi.service.runcontext;

import java.util.Map;

/**
 * Блок промпта в составе контекста рана — wire-форма для {@code GetRunContext}.
 * Контент без тегов/обёртки: XML-тег {@code name}+{@code attrs} ставит рендерер воркера
 * (пустой {@code name} — сырой текст без тега).
 *
 * @param name      имя тега (snake_case) или пустая строка
 * @param source    происхождение: agent | team | skill | guidance | user | connector:&lt;code&gt;
 * @param content   содержимое
 * @param attrs     атрибуты тега
 * @param trusted   false (только user-блоки) → рендерер оборачивает как untrusted data
 * @param ephemeral true → воркер не персистит блок в историю сессии (например memory notes)
 */
public record RunBlock(
        String name,
        String source,
        String content,
        Map<String, String> attrs,
        boolean trusted,
        boolean ephemeral
) {

    public static RunBlock trusted(String name, String source, String content, Map<String, String> attrs) {
        return new RunBlock(name, source, content, attrs, true, false);
    }
}
