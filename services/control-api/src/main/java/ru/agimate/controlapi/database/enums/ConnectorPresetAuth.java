package ru.agimate.controlapi.database.enums;

/**
 * Auth methods a preset's connection form may offer. The stored list is already filtered to what the
 * platform supports: {@code OAUTH} only when the server declares CIMD, {@code TOKEN} and {@code NONE}
 * always. {@code UNKNOWN} is a response-only badge for a card with no supported method; it is never
 * written to {@code auth_methods} (the check constraint rejects it).
 */
public enum ConnectorPresetAuth {
    OAUTH,
    TOKEN,
    NONE,
    UNKNOWN
}
