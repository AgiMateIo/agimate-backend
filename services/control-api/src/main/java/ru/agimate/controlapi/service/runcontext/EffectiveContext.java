package ru.agimate.controlapi.service.runcontext;

import ru.agimate.controlapi.connectors.core.dto.ContextDirectives;

/**
 * Эффективная политика сборки контекста рана: route-пресет ({@link ContextSpec}) ⊕ директивы
 * триггера ({@link ContextDirectives}, статическая декларация кода коннектора). Единственное
 * место наложения — сборка ({@code RunContextService}) читает готовые значения и не ветвится
 * по источнику. {@code directives == null} (триггер без декларации, в т.ч. любой динамический)
 * даёт ровно базовый пресет.
 *
 * @param skillBodies        какие тела скиллов инжектить (route-пресет; директивами не
 *                           переопределяется — в диалоге тела задают поведение, в trigger-ране
 *                           они и есть инструкции обработки)
 * @param triggerGuidance    добавлять system-блок trigger guidance (route-пресет)
 * @param historyDetail      детализация истории (route-пресет)
 * @param historyLimit       окно истории; {@code 0} — история не загружается
 * @param skillTools         собирать тулы скиллов агента
 * @param ownConnectionTools добавить тулы connection события (именно connection, не кода)
 * @param presentation       рендер основного блока события (EVENT | PROMPT)
 * @param promptParam        для PROMPT: параметр {@code data} с текстом
 * @param guidance           trusted user-блок перед блоком события; {@code null} — нет
 */
record EffectiveContext(
        ContextSpec.SkillBodies skillBodies,
        boolean triggerGuidance,
        ContextSpec.HistoryDetail historyDetail,
        int historyLimit,
        boolean skillTools,
        boolean ownConnectionTools,
        ContextDirectives.Presentation presentation,
        String promptParam,
        String guidance
) {

    /** Базовое окно истории (хвост сессии), когда триггер его не переопределил. */
    static final int DEFAULT_HISTORY_LIMIT = 50;

    static EffectiveContext of(ContextSpec base, ContextDirectives d) {
        if (d == null) {
            return new EffectiveContext(base.skillBodies(), base.appendsTriggerGuidance(),
                    base.historyDetail(), DEFAULT_HISTORY_LIMIT, true, false,
                    ContextDirectives.Presentation.EVENT, null, null);
        }
        return new EffectiveContext(
                base.skillBodies(),
                base.appendsTriggerGuidance(),
                base.historyDetail(),
                d.historyLimit() != null ? d.historyLimit() : DEFAULT_HISTORY_LIMIT,
                d.skillTools() == null || d.skillTools(),
                Boolean.TRUE.equals(d.ownConnectionTools()),
                d.presentation() != null ? d.presentation() : ContextDirectives.Presentation.EVENT,
                d.promptParam(),
                blankToNull(d.guidance()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
