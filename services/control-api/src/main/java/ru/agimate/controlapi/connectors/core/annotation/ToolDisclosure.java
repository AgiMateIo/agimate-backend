package ru.agimate.controlapi.connectors.core.annotation;

/**
 * Per-tool override of the connector's disclosure axis ({@link Tool#disclosure()}). Separate from
 * the database enum because an annotation needs a value for «not overridden», and {@code INHERIT}
 * has no place in a column.
 */
public enum ToolDisclosure {
    /** The connector's axis applies. */
    INHERIT,
    /** Always in the context up front, whatever the connector says — for the few most-used tools of a LAZY connector. */
    EAGER,
    /** Listed only, schema on demand — for a rarely used tool of an EAGER connector. */
    LAZY
}
