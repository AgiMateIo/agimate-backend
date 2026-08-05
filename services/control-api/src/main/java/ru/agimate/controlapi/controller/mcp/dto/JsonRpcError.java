package ru.agimate.controlapi.controller.mcp.dto;

/** JSON-RPC 2.0 error object; the codes below are the ones this server can produce. */
public record JsonRpcError(int code, String message) {

    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
}
