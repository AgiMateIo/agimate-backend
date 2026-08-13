package ru.agimate.controlapi.controller.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A JSON-RPC 2.0 response: exactly one of {@code result} / {@code error} is present. Deliberately not
 * wrapped in {@code SuccessResponse} — the envelope here is the protocol's, not ours.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcResponse(
        String jsonrpc,
        Object id,
        Object result,
        JsonRpcError error
) {

    private static final String VERSION = "2.0";

    public static JsonRpcResponse ok(Object id, Object result) {
        return new JsonRpcResponse(VERSION, id, result, null);
    }

    public static JsonRpcResponse error(Object id, int code, String message) {
        return new JsonRpcResponse(VERSION, id, null, new JsonRpcError(code, message));
    }

    public static JsonRpcResponse error(Object id, int code, String message, Object data) {
        return new JsonRpcResponse(VERSION, id, null, new JsonRpcError(code, message, data));
    }
}
