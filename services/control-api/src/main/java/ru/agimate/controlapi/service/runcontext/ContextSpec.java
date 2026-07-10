package ru.agimate.controlapi.service.runcontext;

/**
 * Политика сборки контекста рана (бывший ContextProfile воркера, переехал на бэк).
 * Пресет выбирается по маршруту триггера: есть prompt-канал → {@link #DIALOGUE},
 * иначе {@link #SYSTEM_TRIGGER}. Новые виды входа — новые константы со своей политикой,
 * не условия внутри сборки.
 */
public enum ContextSpec {

    /** Диалог с пользователем: все скиллы перечислены (без тел), тулы всех скиллов. */
    DIALOGUE(false, false),

    /**
     * Автономная обработка события: тела подошедших скиллов инжектятся, тулы — только
     * подошедших скиллов, плюс trigger-guidance блок.
     */
    SYSTEM_TRIGGER(true, true);

    private final boolean loadSkillBodies;
    private final boolean triggerGuidance;

    ContextSpec(boolean loadSkillBodies, boolean triggerGuidance) {
        this.loadSkillBodies = loadSkillBodies;
        this.triggerGuidance = triggerGuidance;
    }

    public boolean loadsSkillBodies() {
        return loadSkillBodies;
    }

    public boolean appendsTriggerGuidance() {
        return triggerGuidance;
    }
}
