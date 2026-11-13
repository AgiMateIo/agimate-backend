package ru.agimate.controlapi.database.enums;

/**
 * The one state of a preset card. Computed from the probe data at seed time and stored, so the
 * catalogue query does not need the raw transport/handshake columns.
 *
 * <p>{@code AVAILABLE} is a hint, not a creation gate: the live {@code validateCredentials} probe
 * remains the final decision.
 */
public enum ConnectorPresetAvailability {
    AVAILABLE,
    DOWN,
    TRANSPORT_UNSUPPORTED,
    PROTOCOL_UNSUPPORTED,
    AUTH_UNSUPPORTED
}
