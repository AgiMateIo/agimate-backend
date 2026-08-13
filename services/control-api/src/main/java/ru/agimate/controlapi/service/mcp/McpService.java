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
import ru.agimate.controlapi.controller.mcp.dto.DiscoverResult;
import ru.agimate.controlapi.controller.mcp.dto.EmptyResult;
import ru.agimate.controlapi.controller.mcp.dto.InitializeResult;
import ru.agimate.controlapi.controller.mcp.dto.JsonRpcError;
import ru.agimate.controlapi.controller.mcp.dto.JsonRpcRequest;
import ru.agimate.controlapi.controller.mcp.dto.JsonRpcResponse;
import ru.agimate.controlapi.controller.mcp.dto.McpTool;
import ru.agimate.controlapi.controller.mcp.dto.TaskResult;
import ru.agimate.controlapi.controller.mcp.dto.ToolCallResult;
import ru.agimate.controlapi.controller.mcp.dto.ToolsListResult;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.security.AgentPrincipal;
import ru.agimate.controlapi.service.AgentService;
import ru.agimate.controlapi.service.dto.ToolResult;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter;
import ru.agimate.controlapi.service.tool.AgentToolCallService;
import ru.agimate.controlapi.service.tool.ToolCallLogService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The MCP surface over an agent's tools: {@code server/discover}, {@code tools/list},
 * {@code tools/call} and the tasks extension ({@code tasks/get}, {@code tasks/update},
 * {@code tasks/cancel}) — plus {@code initialize}, kept beyond the revision.
 *
 * <p>Only the {@code 2026-07-28} revision is served, and only its stateless shape — no sessions, no
 * SSE, no prompts or resources. That is what keeps this a dispatcher over one request: without a
 * server→client channel there is nothing to hold between calls. A task is not held state either —
 * it is the {@code tool_call_logs} row itself ({@code taskId} = {@code external_id}, a task is a
 * row with {@code detached_at}), so any node answers any poll.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpService {

    public static final String PROTOCOL_VERSION = "2026-07-28";

    private static final String SERVER_NAME = "agimate";
    private static final String SERVER_VERSION = "1";

    static final String TASKS_EXTENSION = "io.modelcontextprotocol/tasks";

    private static final String SERVER_INFO_META = "io.modelcontextprotocol/serverInfo";
    private static final String CLIENT_CAPABILITIES_META = "io.modelcontextprotocol/clientCapabilities";

    private static final Map<String, Object> CAPABILITIES = Map.of(
            "tools", Map.of(),
            "extensions", Map.of(TASKS_EXTENSION, Map.of()));

    /**
     * How long a {@code tools/call} without the tasks extension waits. Client-side budgets are of
     * this order, and a connector that needs longer needs a task, not a longer socket.
     */
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(60);

    /**
     * How long a task-capable {@code tools/call} waits before the call detaches into a task: enough
     * for the fast majority to answer in-turn, cheap for the rest. Same reasoning as the worker's
     * {@code agent.tool.detach-after}.
     */
    private static final Duration TASK_GRACE = Duration.ofSeconds(10);

    /**
     * After this {@code tasks/get} answers "expired" (the row itself stays, it is the log). The TTL
     * is the only mechanism against orphans — a restart kills the execution mid-flight and nothing
     * else would ever move such a task out of {@code working}.
     */
    private static final Duration TASK_TTL = Duration.ofHours(24);

    /** Live (detached, unfinished, unexpired) tasks per agent, checked before a call starts. */
    private static final int MAX_LIVE_TASKS = 10;

    private static final int POLL_INTERVAL_MS = 5000;

    private final AgentService agentService;
    private final McpToolCatalog toolCatalog;
    private final AgentToolCallService agentToolCallService;
    private final ToolExecutionService toolExecutionService;
    private final ToolCallLogService toolCallLogService;
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
            case "server/discover" -> JsonRpcResponse.ok(request.id(), discover());
            case "ping" -> JsonRpcResponse.ok(request.id(), EmptyResult.INSTANCE);
            case "tools/list" -> JsonRpcResponse.ok(request.id(), listTools(principal));
            case "tools/call" -> callTool(principal, request);
            case "tasks/get" -> taskMethod(principal, request, this::taskGet);
            case "tasks/cancel" -> taskMethod(principal, request, this::taskCancel);
            case "tasks/update" -> taskMethod(principal, request, this::taskUpdate);
            default -> JsonRpcResponse.error(request.id(), JsonRpcError.METHOD_NOT_FOUND,
                    "Method not found: " + request.method());
        });
    }

    /**
     * Kept beyond the revision: {@code 2026-07-28} replaced the handshake with
     * {@link #discover() server/discover}, but live clients still open with {@code initialize}.
     * The requested version is not negotiated down: this server speaks one revision and says which,
     * leaving the client to decide whether it can talk to it.
     */
    private InitializeResult initialize() {
        return new InitializeResult(
                PROTOCOL_VERSION,
                CAPABILITIES,
                new InitializeResult.ServerInfo(SERVER_NAME, SERVER_VERSION));
    }

    /** Server identity has no field of its own in the revision — it rides {@code _meta}. */
    private DiscoverResult discover() {
        return new DiscoverResult(
                List.of(PROTOCOL_VERSION),
                CAPABILITIES,
                Map.of(SERVER_INFO_META,
                        Map.of("name", SERVER_NAME, "version", SERVER_VERSION)));
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

        // Before the row is created and the tool starts: after the grace it is too late to refuse.
        boolean tasksCapable = declaresTasks(request);
        if (tasksCapable && toolCallLogService.countLiveDetached(
                agent.getId(), LocalDateTime.now().minus(TASK_TTL)) >= MAX_LIVE_TASKS) {
            return JsonRpcResponse.ok(request.id(), ToolCallResult.error(
                    "Too many running tasks (" + MAX_LIVE_TASKS
                            + "); wait for one to complete or cancel it"));
        }

        ToolCallLog toolCallLog;
        try {
            toolCallLog = agentToolCallService.authorizeToolCall(agent.getId(), call);
        } catch (ForbiddenStatusException e) {
            // The tool was listed a moment ago, so this is params_filter or a policy changed mid-flight —
            // the model can act on that, unlike on a transport error.
            return JsonRpcResponse.ok(request.id(), ToolCallResult.error(e.getMessage()));
        }

        ToolExecutionService.WaitOutcome outcome = toolExecutionService.executeWithTimeout(
                toolCallLog, tasksCapable ? TASK_GRACE : TOOL_TIMEOUT);
        if (outcome instanceof ToolExecutionService.WaitOutcome.Completed completed) {
            return JsonRpcResponse.ok(request.id(), toCallResult(completed.result(), entry.spec()));
        }
        if (!tasksCapable) {
            // The pre-tasks contract: the call runs to the end and records its outcome in the log,
            // the client sees a tool error — a transport error would hide it from the model.
            return JsonRpcResponse.ok(request.id(), ToolCallResult.error(
                    "Tool execution timed out after " + TOOL_TIMEOUT.toSeconds() + "s"));
        }
        // The same ownership flip as the worker's detach; losing the stamp race means the tool
        // finished on the boundary, and the client gets the plain result instead of a task.
        ToolCallLog row = toolCallLogService.detach(agent.getId(), toolCallLog.getExternalId());
        if (row.getDetachedAt() == null) {
            return JsonRpcResponse.ok(request.id(), toCallResult(resultOf(row), entry.spec()));
        }
        log.info("MCP task {} created for agent {}: {}.{}", row.getExternalId(), agent.getId(),
                row.getConnectorCode(), row.getName());
        return JsonRpcResponse.ok(request.id(),
                TaskResult.created(row, TASK_TTL.toMillis(), POLL_INTERVAL_MS));
    }

    /**
     * Shared frame of every {@code tasks/*} method: the capability gate, the poll rate limit and
     * the task lookup — a fast synchronous call is not a task ({@code detached_at IS NULL}), and a
     * foreign or unknown {@code taskId} is indistinguishable from a missing one by design.
     */
    private JsonRpcResponse taskMethod(AgentPrincipal principal, JsonRpcRequest request, TaskMethod method) {
        if (!declaresTasks(request)) {
            return JsonRpcResponse.error(request.id(), JsonRpcError.MISSING_CLIENT_CAPABILITY,
                    "Missing required client capability",
                    Map.of("requiredCapabilities", Map.of("extensions", Map.of(TASKS_EXTENSION, Map.of()))));
        }
        if (!rateLimiter.tryAcquire(InboundRateLimiter.Scope.MCP_TASK, principal.agentId())) {
            throw new TooManyRequestsStatusException("MCP task poll rate limit exceeded");
        }
        if (!(request.paramsOrEmpty().get("taskId") instanceof String taskId) || taskId.isBlank()) {
            return JsonRpcResponse.error(request.id(), JsonRpcError.INVALID_PARAMS, "taskId is required");
        }
        ToolCallLog task = toolCallLogService.findByExternalIdAndAgentId(taskId, principal.agentId())
                .filter(t -> t.getDetachedAt() != null)
                .orElse(null);
        if (task == null) {
            return JsonRpcResponse.error(request.id(), JsonRpcError.INVALID_PARAMS,
                    "Task not found: " + taskId);
        }
        if (task.getCreatedAt().plus(TASK_TTL).isBefore(LocalDateTime.now())) {
            return JsonRpcResponse.error(request.id(), JsonRpcError.INVALID_PARAMS, "Task has expired");
        }
        return method.handle(principal, task, request.id());
    }

    @FunctionalInterface
    private interface TaskMethod {
        JsonRpcResponse handle(AgentPrincipal principal, ToolCallLog task, Object requestId);
    }

    private JsonRpcResponse taskGet(AgentPrincipal principal, ToolCallLog task, Object requestId) {
        if (task.getFinishAt() == null) {
            // Also with a pending cancel: cancellation is cooperative, "working" is the honest state.
            return JsonRpcResponse.ok(requestId,
                    TaskResult.working(task, TASK_TTL.toMillis(), POLL_INTERVAL_MS));
        }
        if (task.getCancelRequestedAt() != null) {
            // The stamp lands only while the call runs, so its presence here means the user
            // cancelled that work — the result stays in the log and is not handed out.
            return JsonRpcResponse.ok(requestId,
                    TaskResult.cancelled(task, TASK_TTL.toMillis(), POLL_INTERVAL_MS));
        }
        return JsonRpcResponse.ok(requestId, TaskResult.completed(task,
                completedResult(principal, task), TASK_TTL.toMillis(), POLL_INTERVAL_MS));
    }

    private JsonRpcResponse taskCancel(AgentPrincipal principal, ToolCallLog task, Object requestId) {
        if (toolCallLogService.requestCancel(task.getId())) {
            log.info("MCP task {} cancel requested by agent {}", task.getExternalId(), principal.agentId());
        }
        // 0 rows — the call already finished (the task stays completed) or cancel was already
        // requested; the ack is the same, cancellation promises nothing.
        return JsonRpcResponse.ok(requestId, EmptyResult.INSTANCE);
    }

    private JsonRpcResponse taskUpdate(AgentPrincipal principal, ToolCallLog task, Object requestId) {
        // input_required is never produced, so there is nothing a client could update; the ack
        // keeps the method spec-shaped for clients that probe it.
        return JsonRpcResponse.ok(requestId, EmptyResult.INSTANCE);
    }

    /**
     * The inlined {@code CallToolResult} of a completed task. The spec for {@code structuredContent}
     * is looked up through the catalog again; a binding removed since the call started just means
     * text-only content.
     */
    private ToolCallResult completedResult(AgentPrincipal principal, ToolCallLog task) {
        Agent agent = agentService.findById(principal.agentId());
        ConnectorToolSpec spec = toolCatalog.forAgent(agent).values().stream()
                .filter(e -> e.connectionId().toString().equals(task.getConnectionId())
                        && e.toolName().equals(task.getName()))
                .map(McpToolCatalog.ToolEntry::spec)
                .findFirst()
                .orElse(null);
        return toCallResult(resultOf(task), spec);
    }

    /** The per-request capability gate: a task may only be answered to a request that declared the extension. */
    private static boolean declaresTasks(JsonRpcRequest request) {
        return request.paramsOrEmpty().get("_meta") instanceof Map<?, ?> meta
                && meta.get(CLIENT_CAPABILITIES_META) instanceof Map<?, ?> capabilities
                && capabilities.get("extensions") instanceof Map<?, ?> extensions
                && extensions.containsKey(TASKS_EXTENSION);
    }

    private static ToolResult resultOf(ToolCallLog row) {
        return new ToolResult(row.getExternalId(), row.getConnectorCode(), row.getOutput(), row.getError());
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
        Map<String, Object> structured = spec != null && spec.outputSchema() != null && !output.isBlank()
                ? JsonUtils.fromJsonToMap(output)
                : null;
        return ToolCallResult.text(output, structured);
    }
}
