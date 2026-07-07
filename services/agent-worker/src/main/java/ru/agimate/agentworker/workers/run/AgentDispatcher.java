package ru.agimate.agentworker.workers.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dbos.transact.DBOS;
import dev.dbos.transact.StartWorkflowOptions;
import dev.dbos.transact.workflow.Queue;
import dev.dbos.transact.workflow.WorkflowHandle;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.error.LlmCallError;
import ru.agimate.agentworker.agent.SimpleAgent;
import ru.agimate.agentworker.agent.model.ToolDef;
import ru.agimate.agentworker.agent.ToolRegistry;
import ru.agimate.agentworker.workers.LlmCallWorkflow;
import ru.agimate.agentworker.workers.Queues;
import ru.agimate.agentworker.workers.ToolCallWorkflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-run binding of {@link SimpleAgent}'s injected callables to the worker queues. Each LLM/tool
 * call is enqueued as a child workflow (its own concurrency-limited queue) and awaited. Tool calls
 * are all enqueued in order first, then awaited, so they run concurrently on the tool queue while
 * the enqueue order stays deterministic across DBOS replays. Holds no persistence/output state.
 */
@Slf4j
public class AgentDispatcher implements SimpleAgent.LlmCaller, SimpleAgent.ToolDispatcher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DBOS dbos;
    private final LlmCallWorkflow llm;
    private final ToolCallWorkflow tool;
    private final Queue llmQueue;
    private final Queue toolQueue;
    private final String agentId;
    private final String sessionId;
    private final ToolRegistry registry;

    public AgentDispatcher(DBOS dbos, LlmCallWorkflow llm, ToolCallWorkflow tool, Queue llmQueue, Queue toolQueue,
                           String agentId, String sessionId, ToolRegistry registry) {
        this.dbos = dbos;
        this.llm = llm;
        this.tool = tool;
        this.llmQueue = llmQueue;
        this.toolQueue = toolQueue;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.registry = registry;
    }

    @Override
    public AgentChatMessage call(List<AgentChatMessage> messages, List<ToolDef> toolDefs) {
        WorkflowHandle<LlmCallWorkflow.Result, ? extends Exception> handle =
                dbos.startWorkflow(() -> llm.llmCall(messages, toolDefs, agentId), new StartWorkflowOptions(llmQueue));
        LlmCallWorkflow.Result result = await(handle);
        if (result.failed()) {
            throw new LlmCallError(result.statusCode(), result.message());
        }
        return result.assistant();
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
                    () -> tool.toolCall(bt.connectorCode(), bt.name(), tc.argumentsJson(), toolCallId, agentId, sessionId, bt.identity()),
                    new StartWorkflowOptions(toolQueue));
            pending.add(new Pending(tc, toolCallId, handle, null));
        }

        List<AgentChatMessage.ToolResult> results = new ArrayList<>(pending.size());
        for (Pending p : pending) {
            if (p.immediate != null) {
                results.add(p.immediate);
                continue;
            }
            ToolCallWorkflow.Outcome outcome = await(p.handle);
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

    private static <T> T await(WorkflowHandle<T, ? extends Exception> handle) {
        try {
            return handle.getResult();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Pending(AgentChatMessage.ToolCall call,
                           String toolCallId,
                           WorkflowHandle<ToolCallWorkflow.Outcome, ? extends Exception> handle,
                           AgentChatMessage.ToolResult immediate) {
    }
}
