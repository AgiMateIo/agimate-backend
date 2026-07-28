package ru.agimate.controlapi.service.runcontext;

import ru.agimate.controlapi.connectors.core.dto.ContextDirectives;

/**
 * The effective policy for assembling a run's context: the route preset ({@link ContextSpec}) ⊕ the
 * trigger's directives ({@link ContextDirectives}, a static declaration in the connector's code). The
 * overlay happens in exactly one place — the assembly ({@code RunContextService}) reads finished
 * values and never branches on their source. {@code directives == null} (a trigger with no
 * declaration, any dynamic one included) yields precisely the base preset.
 *
 * @param skillBodies        which skill bodies to inject (the route preset; directives cannot override
 *                           it — in a dialogue the bodies define behaviour, and in a trigger run they
 *                           are the handling instructions)
 * @param triggerGuidance    whether to add the trigger-guidance system block (the route preset)
 * @param historyDetail      level of detail of the history (the route preset)
 * @param historyLimit       window of history; {@code 0} — history is not loaded
 * @param skillTools         whether to collect the agent's skill tools
 * @param ownConnectionTools add the event's connection tools (that connection specifically, not the code)
 * @param presentation       rendering of the event's main block (EVENT | PROMPT)
 * @param promptParam        for PROMPT: the {@code data} parameter holding the text
 * @param guidance           a trusted user block before the event block; {@code null} — none
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

    /** Base window of history (the session's tail) when the trigger did not override it. */
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
