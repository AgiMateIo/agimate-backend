package ru.agimate.controlapi.service.seed;

import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.database.enums.ConnectorPresetAvailability;

/**
 * The seed-time policy behind {@code connector_presets.availability}. The inputs are the probe fields
 * that are deliberately not stored as columns; the result is the one code the catalogue reads.
 *
 * <p>Best-effort by nature: a 401 hides the transport and protocol of an {@code auth_required} server,
 * so that state is accepted when the original URL was not an SSE endpoint. The live
 * {@code validateCredentials} probe remains the final decision.
 */
@UtilityClass
public class PresetAvailabilityPolicy {

    static final String STREAMABLE_HTTP = "streamable-http";
    static final String AUTH_REQUIRED = "auth_required";
    static final String UP = "up";
    static final String DOWN = "down";

    public static ConnectorPresetAvailability evaluate(String status, String transport,
                                                       String transportSource, boolean legacySse,
                                                       Boolean handshakeSupported, boolean hasSupportedAuth) {
        if (!UP.equalsIgnoreCase(status) && !AUTH_REQUIRED.equalsIgnoreCase(status)) {
            return ConnectorPresetAvailability.DOWN;
        }
        if (!STREAMABLE_HTTP.equalsIgnoreCase(transport)) {
            return ConnectorPresetAvailability.TRANSPORT_UNSUPPORTED;
        }
        boolean transportTrusted = "MEASURED".equals(transportSource)
                || "DECLARED".equals(transportSource)
                || (AUTH_REQUIRED.equalsIgnoreCase(status)
                    && "INFERRED".equals(transportSource)
                    && !legacySse);
        if (!transportTrusted) {
            return ConnectorPresetAvailability.TRANSPORT_UNSUPPORTED;
        }
        if (!AUTH_REQUIRED.equalsIgnoreCase(status) && !Boolean.TRUE.equals(handshakeSupported)) {
            return ConnectorPresetAvailability.PROTOCOL_UNSUPPORTED;
        }
        if (!hasSupportedAuth) {
            return ConnectorPresetAvailability.AUTH_UNSUPPORTED;
        }
        return ConnectorPresetAvailability.AVAILABLE;
    }
}
