package ru.agimate.agentworker.workers.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dbos.transact.DBOS;
import dev.dbos.transact.StartWorkflowOptions;
import dev.dbos.transact.workflow.Queue;
import dev.dbos.transact.workflow.WorkflowHandle;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.agent.SimpleAgent;
import ru.agimate.agentworker.agent.ToolRegistry;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.workers.ToolCallWorkflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-run {@link SimpleAgent.ToolDispatcher}: enqueues every tool call in deterministic order first,
 * then awaits them, so they run concurrently on the tool queue while the enqueue order stays stable
 * across DBOS replays. Never throws for a tool failure — a failed call comes back as a failed
 * {@link AgentChatMessage.ToolResult}. Holds no persistence/output state.
 */
@Slf4j
class ToolCallDispatcher implements SimpleAgent.ToolDispatcher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DBOS dbos;
    private final ToolCallWorkflow tool;
    private final Queue toolQueue;
    private final String agentId;
    private final String triggerId;
    private final ToolRegistry registry;

    ToolCallDispatcher(DBOS dbos, ToolCallWorkflow tool, Queue toolQueue, String agentId, String triggerId,
                       ToolRegistry registry) {
        this.dbos = dbos;
        this.tool = tool;
        this.toolQueue = toolQueue;
        this.agentId = agentId;
        this.triggerId = triggerId;
        this.registry = registry;
    }

    @Override
    public List<AgentChatMessage.ToolResult> dispatchAll(List<AgentChatMessage.ToolCall> calls) {
        // Enqueue every call first (deterministic order), then await, so they run concurrently.
        List<Pending> pending = new ArrayList<>(calls.size());
        for (AgentChatMessage.ToolCall tc : calls) {
            // OpenAI always emits a tool_call_id; the fallback covers OpenAI-shim providers
            // (e.g. some Ollama configurations) that occasionally drop it. Generated before the
            // child enqueue, so the checkpoint pins the same id across DBOS replays and the
            // backend's (agent_id, tool_call_id) dedupe key stays unique.
            String toolCallId = effectiveToolCallId(tc.id());
            ToolRegistry.BackendTool bt = registry.resolve(tc.name());
            if (bt == null) {
                log.warn("model called unknown tool {}; {} available: {}", tc.name(), registry.names().size(), registry.names());
                pending.add(new Pending(tc, toolCallId, null, failed(tc, toolCallId,
                        "unknown tool name from model: " + tc.name() + "; available tools: " + registry.names())));
                continue;
            }
            WorkflowHandle<ToolCallWorkflow.Outcome, ? extends Exception> handle = dbos.startWorkflow(
                    () -> tool.toolCall(bt.connectorCode(), bt.name(), tc.argumentsJson(), toolCallId, agentId, triggerId, bt.connectionId()),
                    new StartWorkflowOptions(toolQueue));
            pending.add(new Pending(tc, toolCallId, handle, null));
        }

        List<AgentChatMessage.ToolResult> results = new ArrayList<>(pending.size());
        for (Pending p : pending) {
            if (p.immediate != null) {
                results.add(p.immediate);
                continue;
            }
            ToolCallWorkflow.Outcome outcome = WorkflowHandles.await(p.handle);
            if (outcome.error() != null) {
                results.add(failed(p.call, p.toolCallId(), outcome.error()));
            } else {
                String content = outcome.outputJson() != null && !outcome.outputJson().isEmpty()
                        ? outcome.outputJson() : "null";
                results.add(new AgentChatMessage.ToolResult(p.toolCallId(), p.call.name(), content, false));
            }
        }
        return results;
    }

    /** The LLM-emitted id, or a generated UUID when the provider dropped it. */
    static String effectiveToolCallId(String id) {
        return (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
    }

    private static AgentChatMessage.ToolResult failed(AgentChatMessage.ToolCall tc, String toolCallId, String error) {
        return new AgentChatMessage.ToolResult(toolCallId, tc.name(), errorJson(error), true);
    }

    /** {@code {"error": ...}} via Jackson so any control characters in the message stay valid JSON. */
    static String errorJson(String error) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", error != null ? error : "unknown error"));
        } catch (JsonProcessingException e) {
            return "{\"error\":\"unserializable error message\"}";
        }
    }

    private record Pending(AgentChatMessage.ToolCall call,
                           String toolCallId,
                           WorkflowHandle<ToolCallWorkflow.Outcome, ? extends Exception> handle,
                           AgentChatMessage.ToolResult immediate) {
    }
}
