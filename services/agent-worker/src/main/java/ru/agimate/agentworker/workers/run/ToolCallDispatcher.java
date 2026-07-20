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
import ru.agimate.agentworker.agent.context.ContextBuilder;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.workers.ToolCallWorkflow;

import java.nio.charset.StandardCharsets;
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
            // (e.g. some Ollama configurations) that occasionally drop it.
            String toolCallId = effectiveToolCallId(tc.id());
            ToolRegistry.BackendTool bt = registry.resolve(tc.name());
            if (bt == null) {
                log.warn("model called unknown tool {}; {} available: {}", tc.name(), registry.names().size(), registry.names());
                pending.add(new Pending(tc, toolCallId, null, failed(tc, toolCallId,
                        "unknown tool name from model: " + tc.name() + "; available tools: " + registry.names()),
                        false));
                continue;
            }
            WorkflowHandle<ToolCallWorkflow.Outcome, ? extends Exception> handle = dbos.startWorkflow(
                    () -> tool.toolCall(bt.connectorCode(), bt.name(), tc.argumentsJson(), toolCallId, agentId, triggerId, bt.connectionId(), bt.timeoutSeconds()),
                    new StartWorkflowOptions(toolQueue));
            pending.add(new Pending(tc, toolCallId, handle, null, bt.openWorld()));
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
                if (p.openWorld()) {
                    content = wrapUntrusted(content);
                }
                results.add(new AgentChatMessage.ToolResult(p.toolCallId(), p.call.name(), content, false));
            }
        }
        return results;
    }

    /**
     * Вывод open-world тула ({@code openWorldHint=true}) — чужой контент (письма, тикеты, веб)
     * и канал prompt-injection: оборачивается маркером недоверенных данных. Семантику маркера
     * объясняет system-абзац {@code ContextBuilder.TOOL_OUTPUT_GUIDANCE}; закрывающий тег внутри
     * данных нейтрализуется, чтобы payload не вышел из обёртки. Применяется после трункейта
     * воркера ({@code ToolCallWorkflowImpl}) — обёртка всегда целая.
     */
    static String wrapUntrusted(String content) {
        String tag = ContextBuilder.UNTRUSTED_TOOL_OUTPUT_TAG;
        return "<" + tag + ">\n" + ContextBuilder.neutralizeClosingTag(content, tag) + "\n</" + tag + ">";
    }

    /** Счётчик сгенерированных fallback-id; детерминирован порядком вызовов внутри рана. */
    private int generatedIdSeq = 0;

    /**
     * The LLM-emitted id, or a deterministic fallback when the provider dropped it. The fallback
     * derives from the run id and a per-run counter — never random: the workflow body re-executes
     * on a DBOS crash-replay, and the id must stay the one the backend already executed (and
     * deduped) the call under.
     */
    String effectiveToolCallId(String id) {
        if (id != null && !id.isBlank()) {
            return id;
        }
        return UUID.nameUUIDFromBytes(("agimate-toolcall:" + triggerId + ":" + generatedIdSeq++)
                .getBytes(StandardCharsets.UTF_8)).toString();
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
                           AgentChatMessage.ToolResult immediate,
                           boolean openWorld) {
    }
}
