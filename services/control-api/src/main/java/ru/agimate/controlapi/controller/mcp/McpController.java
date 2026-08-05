package ru.agimate.controlapi.controller.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.controlapi.controller.mcp.dto.JsonRpcError;
import ru.agimate.controlapi.controller.mcp.dto.JsonRpcRequest;
import ru.agimate.controlapi.controller.mcp.dto.JsonRpcResponse;
import ru.agimate.controlapi.security.AgentPrincipal;
import ru.agimate.controlapi.service.mcp.McpService;

/**
 * The MCP endpoint, stateless Streamable HTTP of the {@code 2026-07-28} revision: one JSON-RPC
 * request per POST, and nothing else. Sessions and resumable streams are gone from the revision, so
 * {@code Mcp-Session-Id} and {@code Last-Event-ID} are ignored (never minted, never echoed) and GET
 * or DELETE on the endpoint is a 405 — that is what tells an older client it is talking to a server
 * that does not keep state for it.
 *
 * <p>Responses are not wrapped in {@code SuccessResponse}: the envelope here belongs to JSON-RPC.
 * Authentication is the agent's key ({@code Authorization: Bearer}), and only an agent of type
 * {@code MCP} reaches this path — see {@code AgentAuthFilter} and the api-key chain.
 */
@Slf4j
@RestController
@RequestMapping(McpController.PATH)
@RequiredArgsConstructor
public class McpController {

    public static final String PATH = "/mcp";

    private static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

    private final McpService mcpService;

    @PostMapping
    public ResponseEntity<JsonRpcResponse> handle(
            @RequestBody JsonRpcRequest request,
            @RequestHeader(value = PROTOCOL_VERSION_HEADER, required = false) String protocolVersion,
            @AuthenticationPrincipal AgentPrincipal principal) {

        if (protocolVersion != null && !McpService.PROTOCOL_VERSION.equals(protocolVersion)) {
            return ResponseEntity.badRequest().body(JsonRpcResponse.error(request.id(),
                    JsonRpcError.INVALID_REQUEST,
                    "Unsupported protocol version: " + protocolVersion
                            + "; this server speaks " + McpService.PROTOCOL_VERSION));
        }

        return mcpService.handle(principal, request)
                .map(ResponseEntity::ok)
                // A notification is answered by the transport, not by a body.
                .orElseGet(() -> ResponseEntity.accepted().build());
    }

    @RequestMapping(method = {RequestMethod.GET, RequestMethod.DELETE})
    public ResponseEntity<Void> streamsNotSupported() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }
}
