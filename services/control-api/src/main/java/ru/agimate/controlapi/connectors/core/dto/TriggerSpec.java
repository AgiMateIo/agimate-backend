package ru.agimate.controlapi.connectors.core.dto;

import java.util.List;

/**
 * Declaration of a trigger the connector can produce.
 *
 * @param description human-readable description
 * @param params      names of the parameters available in {@code trigger.data}
 * @param context     context directives of the run ({@code null} — the base route preset); see
 *                    {@link ContextDirectives} — the trust fields are validated at bootstrap
 * @param continuesConversation the event carries on a conversation rather than starting work of its
 *                    own (a subagent's report): a run with no prompt channel but with a conversation
 *                    session is assembled as {@code DIALOGUE_EVENT} — all skill bodies, no trigger
 *                    guidance — instead of {@code SYSTEM_TRIGGER}. A route property, so it is not a
 *                    directive: directives cannot change which skill bodies a run gets
 */
public record TriggerSpec(
        String description,
        List<String> params,
        ContextDirectives context,
        boolean continuesConversation
) {

    public TriggerSpec {
        params = params == null ? List.of() : List.copyOf(params);
    }

    public TriggerSpec(String description, List<String> params, ContextDirectives context) {
        this(description, params, context, false);
    }

    public TriggerSpec(String description, List<String> params) {
        this(description, params, null, false);
    }
}
