package ru.agimate.controlapi.service.mcp.oauth;

import lombok.experimental.UtilityClass;

/** OAuth error codes (RFC 6749, RFC 8707) this authorization server answers with. */
@UtilityClass
public class OAuthErrors {
    public static final String INVALID_REQUEST = "invalid_request";
    public static final String INVALID_GRANT = "invalid_grant";
    public static final String INVALID_TARGET = "invalid_target";
    public static final String UNSUPPORTED_GRANT_TYPE = "unsupported_grant_type";
    public static final String UNSUPPORTED_RESPONSE_TYPE = "unsupported_response_type";
    public static final String ACCESS_DENIED = "access_denied";
    public static final String TEMPORARILY_UNAVAILABLE = "temporarily_unavailable";
}
