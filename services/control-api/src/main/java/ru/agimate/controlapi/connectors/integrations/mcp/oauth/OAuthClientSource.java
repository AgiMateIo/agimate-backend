package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import ru.agimate.controlapi.connectors.core.ConnectorException;

/**
 * Where a client's identity comes from. The two mechanisms are different facts, not two settings of
 * one: CIMD belongs to the installation, DCR belongs to a connection and lives in its grant.
 */
public enum OAuthClientSource {
    CIMD,
    DCR;

    /**
     * A missing source is a secret written before DCR existed — it can only have been CIMD. Any
     * other unknown value is a corrupted secret, not an old one, and is refused rather than guessed.
     */
    public static OAuthClientSource from(String value) {
        if (value == null || value.isBlank()) {
            return CIMD;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Unknown OAuth client source in the connection secret");
        }
    }
}
