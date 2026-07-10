package ru.agimate.agentworker.agent;

import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.agent.error.LlmCallError;
import ru.agimate.agentworker.agent.error.MaxTurnsExceeded;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.ToolDef;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Minimal agent turn-loop over {@link AgentChatMessage}. Drives a model conversation manually so
 * the LLM call and tool calls can be dispatched on separate DBOS queues. The agent knows nothing
 * about DBOS, credentials, transport, or history — the LLM call and the tool dispatcher are
 * injected, and the initial message list is built by the caller.
 *
 * <p>Loop: request the LLM; append the assistant reply; if it has no tool calls, notify and return
 * its text; otherwise dispatch all tool calls, append one tool-result message, notify, and continue
 * — up to {@code maxTurns}.
 */
@Slf4j
public class SimpleAgent {

    /** Injected single-model-request call; throws {@link LlmCallError} on HTTP/API failure. */
    @FunctionalInterface
    public interface LlmCaller {
        AgentChatMessage call(List<AgentChatMessage> messages, List<ToolDef> toolDefs);
    }

    /**
     * Injected tool dispatch: enqueue every call (deterministic order) and return results in order.
     * Never throws for tool failures — a failed call comes back as a failed {@link AgentChatMessage.ToolResult}.
     */
    @FunctionalInterface
    public interface ToolDispatcher {
        List<AgentChatMessage.ToolResult> dispatchAll(List<AgentChatMessage.ToolCall> calls);
    }

    private final LlmCaller llmCaller;
    private final ToolDispatcher toolDispatcher;
    private final List<ToolDef> toolDefs;
    private final int maxTurns;
    private final Consumer<List<AgentChatMessage>> onNewMessages;

    public SimpleAgent(LlmCaller llmCaller, ToolDispatcher toolDispatcher, List<ToolDef> toolDefs,
                       int maxTurns, Consumer<List<AgentChatMessage>> onNewMessages) {
        this.llmCaller = llmCaller;
        this.toolDispatcher = toolDispatcher;
        this.toolDefs = toolDefs;
        this.maxTurns = maxTurns;
        this.onNewMessages = onNewMessages;
    }

    /**
     * Drive the loop starting from {@code messages} (mutated in place). Returns the final text.
     * Throws {@link MaxTurnsExceeded} if no final reply is produced.
     */
    public String run(List<AgentChatMessage> messages) {
        for (int turn = 1; turn <= maxTurns; turn++) {
            log.info("turn {}/{}: requesting LLM", turn, maxTurns);
            AgentChatMessage assistant = llmCaller.call(messages, toolDefs);
            messages.add(assistant);
            List<AgentChatMessage> newInTurn = new ArrayList<>();
            newInTurn.add(assistant);

            if (!assistant.hasToolCalls()) {
                notify(newInTurn);
                String text = assistant.text() != null ? assistant.text() : "";
                log.info("turn {}: final answer ({} chars)", turn, text.length());
                return text;
            }

            log.info("turn {}: dispatching {} tool call(s): {}", turn, assistant.toolCalls().size(),
                    assistant.toolCalls().stream().map(AgentChatMessage.ToolCall::name).toList());
            List<AgentChatMessage.ToolResult> results = toolDispatcher.dispatchAll(assistant.toolCalls());
            AgentChatMessage toolMsg = AgentChatMessage.toolResults(results);
            messages.add(toolMsg);
            newInTurn.add(toolMsg);
            notify(newInTurn);
        }
        throw new MaxTurnsExceeded("agent loop exceeded " + maxTurns + " turns without a final reply");
    }

    private void notify(List<AgentChatMessage> newMessages) {
        if (onNewMessages != null) {
            onNewMessages.accept(newMessages);
        }
    }
}
