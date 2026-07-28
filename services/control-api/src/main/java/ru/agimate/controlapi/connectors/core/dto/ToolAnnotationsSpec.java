package ru.agimate.controlapi.connectors.core.dto;

/**
 * Runtime view of MCP {@code ToolAnnotations} (behavioural hints), so consumers do not depend on the
 * annotation type {@code ToolAnnotations}.
 */
public record ToolAnnotationsSpec(
        boolean readOnlyHint,
        boolean destructiveHint,
        boolean idempotentHint,
        boolean openWorldHint
) {

    /** MCP's (pessimistic) defaults — for when no hints are given. */
    public static final ToolAnnotationsSpec DEFAULT =
            new ToolAnnotationsSpec(false, true, false, true);
}
