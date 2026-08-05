package ru.agimate.controlapi.service.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.TooManyRequestsStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.common.util.UUIDUtils;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.execution.ToolExecutionService;
import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;
import ru.agimate.controlapi.controller.mcp.dto.InitializeResult;
import ru.agimate.controlapi.controller.mcp.dto.JsonRpcError;
import ru.agimate.controlapi.controller.mcp.dto.JsonRpcRequest;
import ru.agimate.controlapi.controller.mcp.dto.JsonRpcResponse;
import ru.agimate.controlapi.controller.mcp.dto.McpTool;
import ru.agimate.controlapi.controller.mcp.dto.ToolCallResult;
import ru.agimate.controlapi.controller.mcp.dto.ToolsListResult;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.security.AgentPrincipal;
import ru.agimate.controlapi.service.AgentService;
import ru.agimate.controlapi.service.dto.ToolResult;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter;
import ru.agimate.controlapi.service.tool.AgentToolCallService;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The MCP surface over an agent's tools: {@code initialize}, {@code tools/list}, {@code tools/call}.
 *
 * <p>Only the {@code 2026-07-28} revision is served, and only its stateless shape — no sessions, no
 * SSE, no tasks, no prompts or resources. That is what keeps this a dispatcher over one request:
 * without a server→client channel there is nothing to hold between calls.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpService {

    public static final String PROTOCOL_VERSION = "2026-07-28";

    private static final String SERVER_NAME = "agimate";
    private static final String SERVER_VERSION = "1";

    /**
     * How long a {@code tools/call} waits. Client-side budgets are of this order, and a connector
     * that needs longer needs a task, not a longer socket.
     */
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(60);

    private final AgentService agentService;
    private final McpToolCatalog toolCatalog;
    private final AgentToolCallService agentToolCallService;
    private final ToolExecutionService toolExecutionService;
    private final InboundRateLimiter rateLimiter;

    /** {@code empty} — a notification: nothing is answered, the transport replies 202. */
    public Optional<JsonRpcResponse> handle(AgentPrincipal principal, JsonRpcRequest request) {
        if (request.isNotification()) {
            log.debug("MCP notification '{}' from agent {}", request.method(), principal.agentId());
            return Optional.empty();
        }
        if (request.method() == null) {
            return Optional.of(JsonRpcResponse.error(request.id(), JsonRpcError.INVALID_REQUEST, "Method is required"));
        }

        return Optional.of(switch (request.method()) {
            case "initialize" -> JsonRpcResponse.ok(request.id(), initialize());
            case "ping" -> JsonRpcResponse.ok(request.id(), Map.of());
            case "tools/list" -> JsonRpcResponse.ok(request.id(), listTools(principal));
            case "tools/call" -> callTool(principal, request);
            default -> JsonRpcResponse.error(request.id(), JsonRpcError.METHOD_NOT_FOUND,
                    "Method not found: " + request.method());
        });
    }

    /**
     * The client's requested version is not negotiated down: this server speaks one revision and says
     * which, leaving the client to decide whether it can talk to it.
     */
    private InitializeResult initialize() {
        return new InitializeResult(
                PROTOCOL_VERSION,
                Map.of("tools", Map.of()),
                new InitializeResult.ServerInfo(SERVER_NAME, SERVER_VERSION));
    }

    private ToolsListResult listTools(AgentPrincipal principal) {
        Agent agent = agentService.findById(principal.agentId());
        List<McpTool> tools = toolCatalog.forAgent(agent).entrySet().stream()
                .map(entry -> McpTool.of(entry.getKey(), entry.getValue().spec()))
                .toList();
        log.debug("MCP tools/list for agent {}: {} tools", agent.getId(), tools.size());
        return new ToolsListResult(tools);
    }

    private JsonRpcResponse callTool(AgentPrincipal principal, JsonRpcRequest request) {
        Map<String, Object> params = request.paramsOrEmpty();
        if (!(params.get("name") instanceof String toolName) || toolName.isBlank()) {
            return JsonRpcResponse.error(request.id(), JsonRpcError.INVALID_PARAMS, "Tool name is required");
        }

        // Before touching the database: the agent is already authenticated by its key.
        if (!rateLimiter.tryAcquire(InboundRateLimiter.Scope.MCP_CALL, principal.agentId())) {
            throw new TooManyRequestsStatusException("MCP call rate limit exceeded");
        }

        Agent agent = agentService.findById(principal.agentId());
        McpToolCatalog.ToolEntry entry = toolCatalog.forAgent(agent).get(toolName);
        if (entry == null) {
            // Includes tools denied by policy: they are not in the listing, and saying why would map
            // the policy for a client that is not entitled to it.
            return JsonRpcResponse.error(request.id(), JsonRpcError.INVALID_PARAMS, "Unknown tool: " + toolName);
        }

        Object arguments = params.get("arguments");
        ToolCallRequest call = ToolCallRequest.builder()
                .id(UUIDUtils.generateUUIDv8().toString())
                .connectorCode(entry.connectorCode())
                .connectionId(entry.connectionId().toString())
                .name(entry.toolName())
                .input(arguments instanceof Map<?, ?> map ? asArguments(map) : Map.of())
                .build();

        ToolCallLog toolCallLog;
        try {
            toolCallLog = agentToolCallService.authorizeToolCall(agent.getId(), call);
        } catch (ForbiddenStatusException e) {
            // The tool was listed a moment ago, so this is params_filter or a policy changed mid-flight —
            // the model can act on that, unlike on a transport error.
            return JsonRpcResponse.ok(request.id(), ToolCallResult.error(e.getMessage()));
        }

        ToolResult result = toolExecutionService.executeWithTimeout(toolCallLog, TOOL_TIMEOUT);
        return JsonRpcResponse.ok(request.id(), toCallResult(result, entry.spec()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asArguments(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /**
     * A tool that declares an {@code outputSchema} owes the client structured content; the same
     * payload also stays as text, for clients that read nothing else.
     */
    private static ToolCallResult toCallResult(ToolResult result, ConnectorToolSpec spec) {
        if (result.getError() != null) {
            return ToolCallResult.error(result.getError());
        }
        String output = result.getOutput() != null ? result.getOutput() : "";
        Map<String, Object> structured = spec.outputSchema() != null && !output.isBlank()
                ? JsonUtils.fromJsonToMap(output)
                : null;
        return ToolCallResult.text(output, structured);
    }
}
