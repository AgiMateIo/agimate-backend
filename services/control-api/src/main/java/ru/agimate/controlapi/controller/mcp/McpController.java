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

import java.util.List;
import java.util.Map;

/**
 * The MCP endpoint, stateless Streamable HTTP of the {@code 2026-07-28} revision: one JSON-RPC
 * request per POST, and nothing else. Sessions and resumable streams are gone from the revision, so
 * {@code Mcp-Session-Id} and {@code Last-Event-ID} are ignored (never minted, never echoed) and GET
 * or DELETE on the endpoint is a 405 — that is what tells an older client it is talking to a server
 * that does not keep state for it.
 *
 * <p>Responses are not wrapped in {@code SuccessResponse}: the envelope here belongs to JSON-RPC.
 * Authentication is the agent's key ({@code Authorization: Bearer}); every external agent type
 * ({@code MCP}, {@code CENTRIFUGO}, {@code WEBHOOK}) reaches this path, {@code GENERIC} does not —
 * its loop is already run by our worker. See {@code AgentAuthFilter} and the api-key chain.
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
                    JsonRpcError.UNSUPPORTED_PROTOCOL_VERSION,
                    "Unsupported protocol version",
                    Map.of("supported", List.of(McpService.PROTOCOL_VERSION), "requested", protocolVersion)));
        }

        return mcpService.handle(principal, request)
                .map(response -> ResponseEntity.status(statusOf(response)).body(response))
                // A notification is answered by the transport, not by a body.
                .orElseGet(() -> ResponseEntity.accepted().build());
    }

    /**
     * The revision pins an HTTP status to some protocol errors; the rest travel as 200, because a
     * tool failure or a policy denial is an answer the model has to read, not a transport fault.
     */
    private static HttpStatus statusOf(JsonRpcResponse response) {
        return response.error() != null
                && response.error().code() == JsonRpcError.MISSING_REQUIRED_CLIENT_CAPABILITY
                ? HttpStatus.BAD_REQUEST
                : HttpStatus.OK;
    }

    @RequestMapping(method = {RequestMethod.GET, RequestMethod.DELETE})
    public ResponseEntity<Void> streamsNotSupported() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }
}
