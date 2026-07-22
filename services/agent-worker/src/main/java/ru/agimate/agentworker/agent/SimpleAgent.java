package ru.agimate.agentworker.agent;

import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.agent.error.ImitationLoopExhausted;
import ru.agimate.agentworker.agent.error.LlmCallError;
import ru.agimate.agentworker.agent.error.MaxTurnsExceeded;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.ToolDef;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Minimal agent turn-loop over {@link AgentChatMessage}. Drives a model conversation manually so
 * the LLM call and tool calls can be dispatched on separate DBOS queues. The agent knows nothing
 * about DBOS, credentials, transport, or history — the LLM call and the tool dispatcher are
 * injected, and the initial message list is built by the caller.
 *
 * <p>Loop: request the LLM; append the assistant reply; if it has no tool calls, notify and return
 * its text; otherwise dispatch all tool calls, append one tool-result message, notify, and continue
 * — up to {@code maxTurns}.
 *
 * <p>Guard: слабые модели (DeepSeek и др.) иногда пишут вызов тула текстом («🔧 name») вместо
 * структурного tool call — без guard'а такой «финальный ответ» тихо завершает ран, а тул не
 * исполняется. Ответ без tool calls, но с паттерном имитации не принимается: в диалог
 * добавляется корректирующий user-ход (до {@value #MAX_IMITATION_CORRECTIONS} раз за ран),
 * и цикл продолжается. Если после исчерпания коррекций модель всё ещё имитирует вызов —
 * ран прерывается {@link ru.agimate.agentworker.agent.error.ImitationLoopExhausted}, чтобы сырая
 * «🔧 …»-строка не ушла юзеру как финальный ответ (корректирующие ходы эфемерны и не проецируются).
 */
@Slf4j
public class SimpleAgent {

    /** Имитация вызова текстом: строка «🔧 …» (канальная проекция) или «[вызван инструмент …]» (история). */
    private static final Pattern TOOL_TEXT_IMITATION =
            Pattern.compile("(?m)^\\s*(🔧|\\[вызван инструмент)");

    static final int MAX_IMITATION_CORRECTIONS = 2;

    static final String IMITATION_CORRECTION =
            "Вызов инструмента, написанный текстом, не исполняется. Если нужно вызвать инструмент — "
            + "сделай настоящий структурный tool call через API. Если вызов не нужен — ответь без "
            + "строк вида «🔧 …».";

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
        int corrections = 0;
        for (int turn = 1; turn <= maxTurns; turn++) {
            log.info("turn {}/{}: requesting LLM", turn, maxTurns);
            AgentChatMessage assistant = llmCaller.call(messages, toolDefs);
            messages.add(assistant);
            List<AgentChatMessage> newInTurn = new ArrayList<>();
            newInTurn.add(assistant);

            if (!assistant.hasToolCalls()) {
                String text = assistant.text() != null ? assistant.text() : "";
                if (TOOL_TEXT_IMITATION.matcher(text).find()) {
                    if (corrections < MAX_IMITATION_CORRECTIONS) {
                        corrections++;
                        log.warn("turn {}: tool call imitated as text, correcting ({}/{})",
                                turn, corrections, MAX_IMITATION_CORRECTIONS);
                        messages.add(AgentChatMessage.user(IMITATION_CORRECTION));
                        continue;
                    }
                    // Коррекции исчерпаны, модель всё ещё имитирует вызов текстом — это не финал.
                    // Не отдаём сырую «🔧 …»-строку юзеру: soft-abort с вежливым нотисом.
                    log.warn("turn {}: still imitating tool call after {} corrections, aborting",
                            turn, MAX_IMITATION_CORRECTIONS);
                    throw new ImitationLoopExhausted("agent kept imitating tool calls as text after "
                            + MAX_IMITATION_CORRECTIONS + " corrections");
                }
                notify(newInTurn);
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
