package ru.agimate.agentworker.workers.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dbos.transact.DBOS;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.GetToolResultResponse;
import ru.agimate.agentworker.ToolResultStatus;
import ru.agimate.agentworker.agent.AgiMateAgent;
import ru.agimate.agentworker.agent.ToolRegistry;
import ru.agimate.agentworker.agent.context.ContextBuilder;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-run {@link AgiMateAgent.ToolDispatcher}: the turn's calls go through one {@code tool_calls}
 * durable step ({@link ToolCallStep}), whose checkpoint is ids and statuses. The contents come from
 * run memory in the normal path and from the backend by id on a crash replay; the worker-side
 * notices (timeout, abandoned, detached) are regenerated. Never throws for a tool failure — a
 * failed call comes back as a failed {@link AgentChatMessage.ToolResult}.
 */
@Slf4j
class ToolCallDispatcher implements AgiMateAgent.ToolDispatcher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DBOS dbos;
    private final ToolCallStep step;
    private final AgentWorkerClient client;
    private final String agentId;
    private final String runId;
    private final ToolRegistry registry;

    ToolCallDispatcher(DBOS dbos, ToolCallStep step, AgentWorkerClient client, String agentId, String runId,
                       ToolRegistry registry) {
        this.dbos = dbos;
        this.step = step;
        this.client = client;
        this.agentId = agentId;
        this.runId = runId;
        this.registry = registry;
    }

    @Override
    public List<AgentChatMessage.ToolResult> dispatchAll(List<AgentChatMessage.ToolCall> calls) {
        List<Planned> planned = new ArrayList<>(calls.size());
        List<ToolCallStep.Call> toIssue = new ArrayList<>(calls.size());
        for (AgentChatMessage.ToolCall tc : calls) {
            // Minted by LlmMessageMapper in place of the provider's, so it is unique across the run
            // and stable across replays — the backend keys tool call idempotency on it.
            ToolRegistry.BackendTool bt = registry.resolve(tc.name());
            if (bt == null) {
                log.warn("model called unknown tool {}; {} available: {}", tc.name(), registry.names().size(), registry.names());
                planned.add(new Planned(tc, null, failed(tc,
                        "unknown tool name from model: " + tc.name() + "; available tools: " + registry.names())));
                continue;
            }
            planned.add(new Planned(tc, bt, null));
            toIssue.add(new ToolCallStep.Call(tc.id(), bt.connectorCode(), bt.connectionId(), bt.name(),
                    tc.argumentsJson(), bt.timeoutSeconds()));
        }

        List<AgentChatMessage.ToolResult> results = new ArrayList<>(planned.size());
        if (toIssue.isEmpty()) {
            planned.forEach(p -> results.add(p.immediate));
            return results;
        }
        // Filled by the step body; empty after a replay, when the contents are re-read by id.
        Map<String, String> held = new HashMap<>();
        ToolCallStep.Outcomes outcomes = dbos.runStep(() -> step.run(toIssue, agentId, runId, held), "tool_calls");

        for (Planned p : planned) {
            if (p.immediate != null) {
                results.add(p.immediate);
                continue;
            }
            results.add(result(p, outcomes.of(p.call.id()), held));
        }
        return results;
    }

    private AgentChatMessage.ToolResult result(Planned p, ToolCallStep.Outcome outcome, Map<String, String> held) {
        String id = p.call.id();
        String name = p.tool.name();
        return switch (outcome.status()) {
            case SUCCESS -> {
                String output = held.containsKey(id) ? held.get(id) : reread(id, ToolResultStatus.TOOL_RESULT_STATUS_SUCCESS);
                String content = output.isEmpty() ? "null" : ToolCallStep.truncateOutput(output, step.maxOutputChars());
                yield new AgentChatMessage.ToolResult(id, p.call.name(), p.tool.openWorld() ? wrapUntrusted(content) : content, false);
            }
            case ERROR -> {
                String error = held.containsKey(id) ? held.get(id) : reread(id, ToolResultStatus.TOOL_RESULT_STATUS_ERROR);
                yield failed(p.call, ToolCallStep.errorNotice(name, error));
            }
            case DETACHED -> new AgentChatMessage.ToolResult(id, p.call.name(), ToolCallStep.detachedInterim(id), false);
            case TIMEOUT -> failed(p.call, ToolCallStep.timeoutNotice(name, id, step.budgetSeconds(p.tool.timeoutSeconds())));
            case ABANDONED -> failed(p.call, ToolCallStep.abandonedNotice(name, id));
            case FAILED -> failed(p.call, ToolCallStep.errorNotice(name, outcome.error()));
        };
    }

    /** The replay path: the checkpoint says the call settled with {@code expected}, the backend holds the content. */
    private String reread(String toolCallId, ToolResultStatus expected) {
        log.info("tool_calls replayed: reading result of {} back from the backend", toolCallId);
        GetToolResultResponse result = client.getToolResult(agentId, toolCallId, runId);
        if (result.getStatus() != expected) {
            throw new IllegalStateException("tool call " + toolCallId + " checkpointed as " + expected
                    + " but the backend answers " + result.getStatus());
        }
        return expected == ToolResultStatus.TOOL_RESULT_STATUS_SUCCESS
                ? result.getOutputJson().toStringUtf8() : result.getError();
    }

    /**
     * Output of an open-world tool ({@code openWorldHint=true}) is third-party content (mail,
     * tickets, the web) and a prompt-injection channel: it gets wrapped in an untrusted-data marker.
     * The marker's meaning is explained by the system paragraph
     * {@code ResponseTemplates.toolOutputGuidance}; a closing tag inside the data is neutralised so
     * the payload cannot escape the wrapper. Applied after the truncation — the wrapper is always intact.
     */
    static String wrapUntrusted(String content) {
        String tag = ContextBuilder.UNTRUSTED_TOOL_OUTPUT_TAG;
        return "<" + tag + ">\n" + ContextBuilder.neutralizeClosingTag(content, tag) + "\n</" + tag + ">";
    }

    private static AgentChatMessage.ToolResult failed(AgentChatMessage.ToolCall tc, String error) {
        return new AgentChatMessage.ToolResult(tc.id(), tc.name(), errorJson(error), true);
    }

    /** {@code {"error": ...}} via Jackson so any control characters in the message stay valid JSON. */
    static String errorJson(String error) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", error != null ? error : "unknown error"));
        } catch (JsonProcessingException e) {
            return "{\"error\":\"unserializable error message\"}";
        }
    }

    private record Planned(AgentChatMessage.ToolCall call, ToolRegistry.BackendTool tool,
                           AgentChatMessage.ToolResult immediate) {
    }
}
