package ru.agimate.controlapi.service.runcontext;

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
     * History without reasoning lines: «💭 thinking...» carries nothing, and in history it reads as
     * an utterance by the agent; tool turns stay as context of past work — structurally
     * (tool_turn → native tool_use/tool_result at the worker), not as text: the textual pattern
     * «🔧 name» is something the model imitates instead of making a real call (legacy rows are
     * sanitised).
     */
    DIALOGUE(SkillBodies.ALL, false, HistoryDetail.NO_REASONING),

    /**
     * Autonomous handling of an event: bodies only of the skills that matched the trigger (they are
     * the instruction for handling the event), tools from every skill, plus the trigger-guidance
     * block.
     */
    SYSTEM_TRIGGER(SkillBodies.MATCHED, true, HistoryDetail.NO_REASONING);

    /** Which skill bodies are injected into the system prompt. */
    public enum SkillBodies {
        /** All of the agent's skills. */
        ALL,
        /** Only skills whose connector_codes contain the trigger's connector. */
        MATCHED
    }

    /** Level of detail of the history the next run sees (a filter by kind/progress_type). */
    public enum HistoryDetail {
        /** Every message, as the user saw it (thinking and tool lines included). */
        FULL,
        /** Without reasoning lines (PROGRESS with progress_type=THINKING). */
        NO_REASONING,
        /** INBOUND/ANSWER/ERROR only — no intermediate steps. */
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
