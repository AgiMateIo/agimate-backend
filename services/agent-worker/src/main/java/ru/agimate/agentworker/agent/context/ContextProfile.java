package ru.agimate.agentworker.agent.context;

/**
 * Input-type profile of an agent run — the declarative context-assembly policy, in one place
 * instead of scattered {@code batch == null} checks. Chosen at the entry point
 * ({@code AgentRunWorkflowImpl}: channel message vs system trigger) and consulted by the
 * materials fetcher (what to load) and {@link ContextBuilder} (how to compose).
 *
 * <p>One more profile-driven difference lives at the entry point itself: the SYSTEM_TRIGGER
 * input is wrapped as untrusted data ({@link RequestBuilder#buildUntrustedTriggerRequest})
 * before it becomes the user turn, while dialogue input reaches the model as-is.
 */
public enum ContextProfile {

    /**
     * A user message from a channel: every skill is listed (metadata only, no bodies) and the
     * toolset spans every skill's connectors.
     */
    DIALOGUE(false, false),

    /**
     * An autonomous system event: only the skill(s) declaring the event's connector are in
     * scope — their bodies are injected, the toolset is scoped to their connectors, and the
     * trigger guidance is appended to the system prompt.
     */
    SYSTEM_TRIGGER(true, true);

    private final boolean loadSkillBodies;
    private final boolean triggerGuidance;

    ContextProfile(boolean loadSkillBodies, boolean triggerGuidance) {
        this.loadSkillBodies = loadSkillBodies;
        this.triggerGuidance = triggerGuidance;
    }

    /**
     * Scope skills to the trigger batch and load their {@code skill_md} bodies (which also
     * narrows the toolset to the scoped skills' connectors).
     */
    public boolean loadsSkillBodies() {
        return loadSkillBodies;
    }

    /** Append {@link SystemPromptBuilder#TRIGGER_GUIDANCE} to the system prompt. */
    public boolean appendsTriggerGuidance() {
        return triggerGuidance;
    }
}
