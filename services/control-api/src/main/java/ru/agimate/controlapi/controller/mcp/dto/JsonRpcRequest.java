package ru.agimate.controlapi.controller.mcp.dto;

import java.util.Map;

/**
 * A single JSON-RPC 2.0 call. Batches were removed from MCP, so one request per POST.
 *
 * @param id     absent for a notification — the one case where nothing is answered; a string or a
 *               number by the spec, so it travels as-is and is echoed back untouched
 * @param params free-form by method; kept as a map to stay clear of the two Jackson versions living
 *               in this service
 */
public record JsonRpcRequest(
        String jsonrpc,
        Object id,
        String method,
        Map<String, Object> params
) {

    public boolean isNotification() {
        return id == null;
    }

    public Map<String, Object> paramsOrEmpty() {
        return params != null ? params : Map.of();
    }
}
