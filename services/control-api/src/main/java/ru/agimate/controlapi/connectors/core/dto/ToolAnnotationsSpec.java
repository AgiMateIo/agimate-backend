package ru.agimate.controlapi.connectors.core.dto;

/**
 * Рантайм-вид MCP {@code ToolAnnotations} (поведенческие хинты), чтобы потребители не зависели
 * от типа аннотации {@code ToolAnnotations}.
 */
public record ToolAnnotationsSpec(
        boolean readOnlyHint,
        boolean destructiveHint,
        boolean idempotentHint,
        boolean openWorldHint
) {

    /** Дефолты MCP (пессимистичные) — когда хинты не заданы. */
    public static final ToolAnnotationsSpec DEFAULT =
            new ToolAnnotationsSpec(false, true, false, true);
}
