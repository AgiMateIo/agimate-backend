package ru.agimate.controlapi.service.runcontext;

import java.util.Set;

/**
 * Policy for assembling a run's context (the worker's former ContextProfile, moved to the backend).
 * The preset is chosen by the trigger's route: a prompt channel exists → {@link #DIALOGUE},
 * otherwise {@link #SYSTEM_TRIGGER}. New kinds of input mean new constants with their own policy,
 * not conditionals inside the assembly.
 */
public enum ContextSpec {

    /**
     * A dialogue with the user: the bodies and tools of all the agent's skills — skills define
     * behaviour in a dialogue too (media iteration discipline, memory note rules), not only in
     * trigger runs; the bodies are stable and friendly to the prompt cache.
     */
    DIALOGUE(SkillBodies.ALL, false, Set.of(HistoryPart.DIALOG, HistoryPart.TOOLS), false),

    /**
     * Autonomous handling of an event: bodies only of the skills that matched the trigger (they are
     * the instruction for handling the event), tools from every skill, plus the trigger-guidance
     * block.
     */
    SYSTEM_TRIGGER(SkillBodies.MATCHED, true, Set.of(HistoryPart.DIALOG, HistoryPart.TOOLS), true);

    /** Which skill bodies are injected into the system prompt. */
    public enum SkillBodies {
        /** All of the agent's skills. */
        ALL,
        /** Only skills whose connector_codes contain the trigger's connector. */
        MATCHED
    }

    /**
     * A part of a past run that the next one gets to see. A set rather than a scale: «everything but
     * reasoning» only reads as a level if you already know what «everything» contains.
     */
    public enum HistoryPart {
        /** The exchange itself: the user's message and the model's answer. */
        DIALOG,
        /** Tool calls and their results — handed over structurally, as a native tool_use/tool_result pair. */
        TOOLS,
        /**
         * The model's reasoning. No preset selects it: a provider will not accept replayed reasoning
         * without the signatures it issued, and we do not keep those.
         */
        REASONING
    }

    private final SkillBodies skillBodies;
    private final boolean triggerGuidance;
    private final Set<HistoryPart> historyParts;
    private final boolean upfront;

    ContextSpec(SkillBodies skillBodies, boolean triggerGuidance, Set<HistoryPart> historyParts, boolean upfront) {
        this.skillBodies = skillBodies;
        this.triggerGuidance = triggerGuidance;
        this.historyParts = historyParts;
        this.upfront = upfront;
    }

    public SkillBodies skillBodies() {
        return skillBodies;
    }

    public boolean appendsTriggerGuidance() {
        return triggerGuidance;
    }

    public Set<HistoryPart> historyParts() {
        return historyParts;
    }

    /**
     * Progressive disclosure happens up front rather than on demand: the selected skill bodies and
     * the event connector's tool schemas ship whole whatever their axis says. A trigger run has no
     * user waiting, so an extra disclosure turn is pure loss — and no history window of its own to
     * derive the disclosed set from.
     */
    public boolean disclosesUpfront() {
        return upfront;
    }
}
