package ru.agimate.agentworker.agent;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Minimal agent turn-loop over {@link AgentChatMessage}. Drives a model conversation manually so
 * the LLM call and tool calls can be dispatched on separate DBOS queues. The agent knows nothing
 * about DBOS, credentials, transport, or history — the LLM call, the tool dispatcher, and (for
 * steering) the checkpoint are injected, and the initial message list is built by the caller.
 *
 * <p>Loop: request the LLM; append the assistant reply; if it has no tool calls, notify and return
 * its text; otherwise dispatch all tool calls, append one tool-result message, notify, and continue
 * — up to {@code maxTurns}. After persisting each turn a {@link Checkpointer} (if set) is consulted:
 * it may inject steer messages (fold new user input into the run) or request a graceful interrupt.
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

    /** Injected steering hook consulted after each persisted turn. {@code phase} is {@code "turn"}/{@code "tool_result"}. */
    @FunctionalInterface
    public interface Checkpointer {
        CheckpointResult checkpoint(List<AgentChatMessage> messages, String phase);
    }

    /** Outcome of a checkpoint: user messages to fold in, and/or a request to interrupt gracefully. */
    public record CheckpointResult(List<AgentChatMessage> injected, boolean cancel) {
        public static final CheckpointResult NONE = new CheckpointResult(List.of(), false);
    }

    private final LlmCaller llmCaller;
    private final ToolDispatcher toolDispatcher;
    private final List<ToolDef> toolDefs;
    private final int maxTurns;
    private final Consumer<List<AgentChatMessage>> onNewMessages;
    private final Checkpointer checkpointer;
    private final Consumer<String> onInterimAnswer;

    public SimpleAgent(LlmCaller llmCaller, ToolDispatcher toolDispatcher, List<ToolDef> toolDefs,
                       int maxTurns, Consumer<List<AgentChatMessage>> onNewMessages, Checkpointer checkpointer,
                       Consumer<String> onInterimAnswer) {
        this.llmCaller = llmCaller;
        this.toolDispatcher = toolDispatcher;
        this.toolDefs = toolDefs;
        this.maxTurns = maxTurns;
        this.onNewMessages = onNewMessages;
        this.checkpointer = checkpointer;
        this.onInterimAnswer = onInterimAnswer;
    }

    /**
     * Drive the loop starting from {@code messages} (mutated in place). Returns the final text.
     * Throws {@link MaxTurnsExceeded} if no final reply is produced, or {@link AgentInterrupted} if
     * a checkpoint requested a graceful stop.
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
                // Boundary A: a steer arriving just as we finish keeps the loop going instead of returning.
                CheckpointResult cp = checkpoint(messages, "turn");
                if (cp.cancel()) {
                    throw new AgentInterrupted();
                }
                if (!cp.injected().isEmpty()) {
                    // A steer folded in just as the assistant finished: deliver the completed
                    // answer now — otherwise the reply to the earlier message would be persisted
                    // to history but never reach the user's channel.
                    if (onInterimAnswer != null && assistant.text() != null && !assistant.text().isEmpty()) {
                        onInterimAnswer.accept(assistant.text());
                    }
                    messages.addAll(cp.injected());
                    notify(cp.injected());
                    continue;
                }
                String text = assistant.text() != null ? assistant.text() : "";
                log.info("turn {}: final answer ({} chars)", turn, text.length());
                return text;
            }

            log.info("turn {}: dispatching {} tool call(s)", turn, assistant.toolCalls().size());
            List<AgentChatMessage.ToolResult> results = toolDispatcher.dispatchAll(assistant.toolCalls());
            AgentChatMessage toolMsg = AgentChatMessage.toolResults(results);
            messages.add(toolMsg);
            newInTurn.add(toolMsg);
            notify(newInTurn);

            // Boundary B: fold in any steer after the tool round; an interrupt exits cleanly.
            CheckpointResult cp = checkpoint(messages, "tool_result");
            if (cp.cancel()) {
                throw new AgentInterrupted();
            }
            if (!cp.injected().isEmpty()) {
                messages.addAll(cp.injected());
                notify(cp.injected());
            }
        }
        throw new MaxTurnsExceeded("agent loop exceeded " + maxTurns + " turns without a final reply");
    }

    private CheckpointResult checkpoint(List<AgentChatMessage> messages, String phase) {
        return checkpointer != null ? checkpointer.checkpoint(messages, phase) : CheckpointResult.NONE;
    }

    private void notify(List<AgentChatMessage> newMessages) {
        if (onNewMessages != null) {
            onNewMessages.accept(newMessages);
        }
    }
}
