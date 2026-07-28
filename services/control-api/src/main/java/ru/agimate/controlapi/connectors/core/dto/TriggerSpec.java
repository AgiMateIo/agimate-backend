package ru.agimate.controlapi.connectors.core.dto;

import java.util.List;

/**
 * Declaration of a trigger the connector can produce.
 *
 * @param description human-readable description
 * @param params      names of the parameters available in {@code trigger.data}
 * @param context     context directives of the run ({@code null} — the base route preset); see
 *                    {@link ContextDirectives} — the trust fields are validated at bootstrap
 */
public record TriggerSpec(
        String description,
        List<String> params,
        ContextDirectives context
) {

    public TriggerSpec {
        params = params == null ? List.of() : List.copyOf(params);
    }

    public TriggerSpec(String description, List<String> params) {
        this(description, params, null);
    }
}
