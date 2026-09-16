package ru.agimate.controlapi.controller.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JSON-RPC 2.0 error object; the codes below are the ones this server can produce.
 *
 * <p>The revision's own codes come from {@code -32020..-32099}, the sub-range it reserves for itself;
 * {@code -32000..-32019} is implementation-defined, which is why the pre-release numbers there were
 * moved rather than kept.
 *
 * @param data structured detail where the spec shapes one — {@code requiredCapabilities} on
 *             {@link #MISSING_REQUIRED_CLIENT_CAPABILITY}, {@code supported}/{@code requested} on
 *             {@link #UNSUPPORTED_PROTOCOL_VERSION}; null otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcError(int code, String message, Object data) {

    public JsonRpcError(int code, String message) {
        this(code, message, null);
    }

    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    /** The request needs an extension the client did not declare in its per-request capabilities. */
    public static final int MISSING_REQUIRED_CLIENT_CAPABILITY = -32021;
    public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;
}
