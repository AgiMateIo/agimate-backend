package ru.agimate.controlapi.connectors.core.dto;

import lombok.Builder;

/**
 * Context directives of a trigger — an overlay on top of the route preset ({@code ContextSpec}): a
 * {@code null} field means «as in the base». Declared <b>by connector code only</b>, in the static
 * {@link TriggerSpec} ({@code TriggerProvider.getTriggers()}); dynamic declarations
 * ({@code connection_triggers}) and the event payload can never be a source of directives — an
 * unfamiliar trigger gets the base preset (default-safe).
 *
 * <p>The fields fall into two risk classes:
 * <ul>
 *   <li><b>trust</b> — {@link #presentation}/{@link #promptParam}, {@link #guidance}: they change how
 *       much the prompt's text is trusted. Allowed for internal connectors only — fail-fast
 *       validation in {@code ConnectorBootstrap}. {@code guidance} is a static code constant, with no
 *       interpolation of event data;</li>
 *   <li><b>scope</b> — the rest: they change the volume of context (tools, skill bodies, history) and
 *       leave trust alone.</li>
 * </ul>
 *
 * @param presentation       how to render the event: {@code EVENT} (untrusted JSON, the default) or
 *                           {@code PROMPT} — trusted text from {@code data[promptParam]} (the first
 *                           consumer is {@code time.due}: a prompt authored by the agent itself)
 * @param promptParam        for {@code PROMPT}: the name of the {@code data} parameter holding the
 *                           prompt text
 * @param guidance           a trusted user block immediately before the event block: provenance and
 *                           what to do (the first consumer is {@code time.due})
 * @param skillTools         {@code false} — do not collect the agent's skill tools (the first
 *                           consumers are the memory triggers: minimal context); the default is
 *                           {@code true}
 * @param ownConnectionTools {@code true} — add the event's connection tools regardless of skills; the
 *                           scope is the trigger's connection specifically, not every connection of
 *                           its code (INSTANCE connectors). The first consumers are {@code time.due}
 *                           (cancel/reschedule) and the memory triggers
 * @param historyLimit       window of session history; {@code 0} — no history (the first consumers are
 *                           the memory triggers: the messages are already in {@code data});
 *                           {@code null} — the base
 */
@Builder
public record ContextDirectives(
        Presentation presentation,
        String promptParam,
        String guidance,
        Boolean skillTools,
        Boolean ownConnectionTools,
        Integer historyLimit
) {

    /** Rendering of the event's main user block. */
    public enum Presentation { EVENT, PROMPT }
}
