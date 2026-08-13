package ru.agimate.controlapi.controller.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JSON-RPC 2.0 error object; the codes below are the ones this server can produce.
 *
 * @param data structured detail where the spec shapes one — {@code requiredCapabilities} on
 *             {@link #MISSING_CLIENT_CAPABILITY}; null otherwise
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
    public static final int MISSING_CLIENT_CAPABILITY = -32003;
}
