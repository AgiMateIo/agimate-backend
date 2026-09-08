package ru.agimate.controlapi.database.enums;

/**
 * Progressive disclosure axis ({@code docs/decisions/progressive-disclosure.md}): whether a
 * connector's tool schemas, or a skill's body, go into the run context up front or on demand.
 * Declared on the connector ({@code ConnectorTraits}), overridable per tool ({@code @Tool}), and on
 * the skill ({@code SKILL.md} frontmatter). Read only when the run has a disclosing connector —
 * without one everything is EAGER, as before the axis existed.
 */
public enum Disclosure {
    /** Full schema / full body in the context from the first turn. */
    EAGER,
    /** Listed by name and summary; the schema or body arrives on a {@code load_tools}/{@code load_skill} call. */
    LAZY
}
