package ru.agimate.controlapi.service.runcontext;

/**
 * Политика сборки контекста рана (бывший ContextProfile воркера, переехал на бэк).
 * Пресет выбирается по маршруту триггера: есть prompt-канал → {@link #DIALOGUE},
 * иначе {@link #SYSTEM_TRIGGER}. Новые виды входа — новые константы со своей политикой,
 * не условия внутри сборки.
 */
public enum ContextSpec {

    /** Диалог с пользователем: все скиллы перечислены (без тел), тулы всех скиллов. */
    DIALOGUE(false, false, HistoryDetail.FULL),

    /**
     * Автономная обработка события: тела подошедших скиллов инжектятся, тулы — только
     * подошедших скиллов, плюс trigger-guidance блок.
     */
    SYSTEM_TRIGGER(true, true, HistoryDetail.FULL);

    /** Детализация истории, которую видит следующий ран (фильтр по kind/progress_type). */
    public enum HistoryDetail {
        /** Все сообщения, как их видел пользователь (включая thinking/tool-строки). */
        FULL,
        /** Без reasoning-строк (PROGRESS c progress_type=THINKING). */
        NO_REASONING,
        /** Только INBOUND/ANSWER/ERROR — без промежуточных шагов. */
        DIALOGUE_ONLY
    }

    private final boolean loadSkillBodies;
    private final boolean triggerGuidance;
    private final HistoryDetail historyDetail;

    ContextSpec(boolean loadSkillBodies, boolean triggerGuidance, HistoryDetail historyDetail) {
        this.loadSkillBodies = loadSkillBodies;
        this.triggerGuidance = triggerGuidance;
        this.historyDetail = historyDetail;
    }

    public boolean loadsSkillBodies() {
        return loadSkillBodies;
    }

    public boolean appendsTriggerGuidance() {
        return triggerGuidance;
    }

    public HistoryDetail historyDetail() {
        return historyDetail;
    }
}
