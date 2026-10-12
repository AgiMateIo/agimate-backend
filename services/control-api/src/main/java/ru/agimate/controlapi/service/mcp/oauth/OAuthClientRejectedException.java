package ru.agimate.controlapi.service.mcp.oauth;

/**
 * The client cannot be identified or its redirect target cannot be trusted. Never answered with a
 * redirect: sending the browser to an unverified address is an open redirector (RFC 6749, 4.1.2.1).
 */
public class OAuthClientRejectedException extends RuntimeException {
    public OAuthClientRejectedException(String message) {
        super(message);
    }
}
