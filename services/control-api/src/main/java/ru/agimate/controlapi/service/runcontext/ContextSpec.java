package ru.agimate.controlapi.service.runcontext;

/**
 * Политика сборки контекста рана (бывший ContextProfile воркера, переехал на бэк).
 * Пресет выбирается по маршруту триггера: есть prompt-канал → {@link #DIALOGUE},
 * иначе {@link #SYSTEM_TRIGGER}. Новые виды входа — новые константы со своей политикой,
 * не условия внутри сборки.
 */
public enum ContextSpec {

    /**
     * Диалог с пользователем: тела и тулы всех скиллов агента — скиллы задают поведение
     * и в диалоге (дисциплина итераций media, правила заметок памяти), а не только в
     * trigger-ранах; тела стабильны и дружат с prompt-кэшем.
     * История без reasoning-строк: «💭 thinking...» бессодержательна, а в истории читается
     * как реплика агента; tool-ходы остаются как контекст прошлой работы — структурно
     * (tool_turn → нативные tool_use/tool_result у воркера), не текстом: текстовый паттерн
     * «🔧 name» модель имитирует вместо реального вызова (легаси-строки санитизируются).
     */
    DIALOGUE(SkillBodies.ALL, false, HistoryDetail.NO_REASONING),

    /**
     * Автономная обработка события: тела — только подошедших триггеру скиллов (они и есть
     * инструкция обработки события), тулы — всех скиллов, плюс trigger-guidance блок.
     */
    SYSTEM_TRIGGER(SkillBodies.MATCHED, true, HistoryDetail.NO_REASONING);

    /** Какие тела скиллов инжектятся в системный промпт. */
    public enum SkillBodies {
        /** Все скиллы агента. */
        ALL,
        /** Только скиллы, чьи connector_codes содержат коннектор триггера. */
        MATCHED
    }

    /** Детализация истории, которую видит следующий ран (фильтр по kind/progress_type). */
    public enum HistoryDetail {
        /** Все сообщения, как их видел пользователь (включая thinking/tool-строки). */
        FULL,
        /** Без reasoning-строк (PROGRESS c progress_type=THINKING). */
        NO_REASONING,
        /** Только INBOUND/ANSWER/ERROR — без промежуточных шагов. */
        DIALOGUE_ONLY
    }

    private final SkillBodies skillBodies;
    private final boolean triggerGuidance;
    private final HistoryDetail historyDetail;

    ContextSpec(SkillBodies skillBodies, boolean triggerGuidance, HistoryDetail historyDetail) {
        this.skillBodies = skillBodies;
        this.triggerGuidance = triggerGuidance;
        this.historyDetail = historyDetail;
    }

    public SkillBodies skillBodies() {
        return skillBodies;
    }

    public boolean appendsTriggerGuidance() {
        return triggerGuidance;
    }

    public HistoryDetail historyDetail() {
        return historyDetail;
    }
}
