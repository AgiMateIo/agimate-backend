package ru.agimate.agentworker.agent;

import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.agent.error.ImitationLoopExhausted;
import ru.agimate.agentworker.agent.error.LlmCallError;
import ru.agimate.agentworker.agent.error.LlmResponseIncomplete;
import ru.agimate.agentworker.agent.error.MaxTurnsExceeded;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.LlmMeta;
import ru.agimate.agentworker.agent.model.LlmUsage;
import ru.agimate.agentworker.agent.model.ToolDef;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Minimal agent turn-loop over {@link AgentChatMessage}. Drives a model conversation manually so
 * the LLM call and tool calls can be dispatched on separate DBOS queues. The agent knows nothing
 * about DBOS, credentials, transport, or history — the LLM call and the tool dispatcher are
 * injected, and the initial message list is built by the caller.
 *
 * <p>Loop: request the LLM; append the assistant reply; if it has no tool calls, notify and return
 * its text; otherwise notify the assistant's calls, dispatch them all, append one tool-result
 * message, notify it separately, and continue — up to {@code maxTurns}. The two notifies per tool
 * turn (calls before dispatch, results after) are what let the backend record and deliver the tool
 * call the moment it is made, ahead of the — possibly slow — execution.
 *
 * <p>Guard: weak models (DeepSeek and others) sometimes write a tool call out as text («🔧 name»)
 * instead of making a structural one — without the guard such a «final answer» quietly ends the run
 * while the tool never executes. A reply with no tool calls but matching the imitation pattern is
 * not accepted: a corrective user turn is appended to the conversation (up to
 * {@value #MAX_IMITATION_CORRECTIONS} times per run) and the loop continues. If the model still
 * imitates a call once the corrections are exhausted, the run aborts with
 * {@link ru.agimate.agentworker.agent.error.ImitationLoopExhausted}, so a raw «🔧 …» line never
 * reaches the user as a final answer (corrective turns are ephemeral and are not projected).
 *
 * <p>Soft landing at the cap: {@value #WRAP_UP_TURNS} turns before the limit an ephemeral wrap-up
 * notice is injected («finish with what you have»), and the last turn runs <b>without tools</b> —
 * forcing the model to produce final text. Iterative perfectionism (generate → check → «not quite»
 * → again) then ends in a degraded but useful answer with the artefacts already produced, rather
 * than in {@link MaxTurnsExceeded} with all the work lost. {@code MaxTurnsExceeded} remains
 * possible only if the model fails to answer even on the tool-less turn.
 */
@Slf4j
public class SimpleAgent {

    /** Imitating a call as text: a «🔧 …» line (the channel projection) or «[вызван инструмент …]» (history). */
    private static final Pattern TOOL_TEXT_IMITATION =
            Pattern.compile("(?m)^\\s*(🔧|\\[вызван инструмент)");

    static final int MAX_IMITATION_CORRECTIONS = 2;

    static final String IMITATION_CORRECTION =
            "Вызов инструмента, написанный текстом, не исполняется. Если нужно вызвать инструмент — "
            + "сделай настоящий структурный tool call через API. Если вызов не нужен — ответь без "
            + "строк вида «🔧 …».";

    /** How many turns before the cap the wrap-up notice is injected; the last turn runs without tools. */
    static final int WRAP_UP_TURNS = 2;

    static final String WRAP_UP_NOTICE =
            "Бюджет шагов рана почти исчерпан: осталось не более двух ходов. Заверши работу сейчас — "
            + "дай пользователю финальный ответ из того, что уже готово (приложи готовые файлы и "
            + "результаты), и явно отметь, что сделать не успел. Новый вызов инструмента — только "
            + "если без него ответ невозможен.";

    /** Injected single-model-request call; throws {@link LlmCallError} on HTTP/API failure. */
    @FunctionalInterface
    public interface LlmCaller {
        LlmReply call(List<AgentChatMessage> messages, List<ToolDef> toolDefs);
    }

    /**
     * An LLM reply: the assistant message plus its provenance ({@code meta}) for the turn ledger,
     * the call's token {@code usage} ({@code null} when nothing to account), and — for a truncated
     * call — the terminal {@code incompleteReason} ({@code null} on a normal finish). The loop
     * surfaces {@code usage} <b>before</b> acting on {@code incompleteReason}, so a truncated call's
     * tokens are still accounted although the turn aborts.
     */
    public record LlmReply(AgentChatMessage message, LlmMeta meta, LlmUsage usage,
                           LlmResponseIncomplete.Reason incompleteReason) {
        public static LlmReply of(AgentChatMessage message) {
            return new LlmReply(message, null, null, null);
        }
    }

    /**
     * Injected tool dispatch: enqueue every call (deterministic order) and return results in order.
     * Never throws for tool failures — a failed call comes back as a failed {@link AgentChatMessage.ToolResult}.
     */
    @FunctionalInterface
    public interface ToolDispatcher {
        List<AgentChatMessage.ToolResult> dispatchAll(List<AgentChatMessage.ToolCall> calls);
    }

    /**
     * Observer of the run's loop events — the run wiring projects each into a backend side-record,
     * so the loop stays pure and the parent stays the sole writer. Default no-ops let a caller (or
     * test) handle only the events it cares about; {@link #NOOP} ignores all.
     */
    public interface RunObserver {
        /**
         * Fires once with the initial message list <b>before</b> the first model call — exactly what
         * the LLM sees on turn 1 (system + history + trigger). Snapshotted into {@code agent_runs.prompt}.
         */
        default void onStart(List<AgentChatMessage> messages) {}

        /**
         * New dialogue messages plus the LLM {@code meta} of the turn that produced them ({@code null}
         * for tool-result turns — no LLM call). Persistence and channel delivery are its projections.
         */
        default void onMessages(List<AgentChatMessage> messages, LlmMeta meta) {}

        /**
         * Per-call token usage for every model call that reached the provider (happy, imitation, and
         * truncated), before the loop acts on the reply.
         */
        default void onUsage(LlmUsage usage) {}

        RunObserver NOOP = new RunObserver() {};
    }

    private final LlmCaller llmCaller;
    private final ToolDispatcher toolDispatcher;
    private final List<ToolDef> toolDefs;
    private final int maxTurns;
    private final RunObserver observer;

    public SimpleAgent(LlmCaller llmCaller, ToolDispatcher toolDispatcher, List<ToolDef> toolDefs,
                       int maxTurns, RunObserver observer) {
        this.llmCaller = llmCaller;
        this.toolDispatcher = toolDispatcher;
        this.toolDefs = toolDefs;
        this.maxTurns = maxTurns;
        this.observer = observer != null ? observer : RunObserver.NOOP;
    }

    /**
     * Drive the loop starting from {@code messages} (mutated in place). Returns the final text.
     * Throws {@link MaxTurnsExceeded} if no final reply is produced.
     */
    public String run(List<AgentChatMessage> messages) {
        notifyStart(messages);
        int corrections = 0;
        // Soft landing only for a meaningful cap — a tiny maxTurns (tests, debugging) is left alone.
        boolean softLanding = maxTurns > WRAP_UP_TURNS;
        for (int turn = 1; turn <= maxTurns; turn++) {
            if (softLanding && turn == maxTurns - WRAP_UP_TURNS + 1) {
                // An ephemeral turn (no notify): it is projected neither into history nor into the channel, like an imitation correction.
                log.info("turn {}/{}: injecting wrap-up notice", turn, maxTurns);
                messages.add(AgentChatMessage.user(WRAP_UP_NOTICE));
            }
            boolean toolless = softLanding && turn == maxTurns;
            log.info("turn {}/{}: requesting LLM{}", turn, maxTurns, toolless ? " (tool-less final)" : "");
            LlmReply reply = llmCaller.call(messages, toolless ? List.of() : toolDefs);
            // Usage accounting comes before any decision: a truncated call has already spent its tokens
            // while the turn itself is about to break off. We surface it into the sink, and the run wiring
            // reports it (the single writer of side records).
            notifyUsage(reply.usage());
            if (reply.incompleteReason() != null) {
                throw new LlmResponseIncomplete(reply.incompleteReason());
            }
            AgentChatMessage assistant = reply.message();
            messages.add(assistant);

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
                    // The corrections are exhausted and the model still imitates a call as text — this is no final answer.
                    // We do not hand the raw «🔧 …» line to the user: a soft abort with a polite notice.
                    log.warn("turn {}: still imitating tool call after {} corrections, aborting",
                            turn, MAX_IMITATION_CORRECTIONS);
                    throw new ImitationLoopExhausted("agent kept imitating tool calls as text after "
                            + MAX_IMITATION_CORRECTIONS + " corrections");
                }
                notify(List.of(assistant), reply.meta());
                log.info("turn {}: final answer ({} chars)", turn, text.length());
                return text;
            }

            // Two turn events: first the calls (delivered into the channel before execution), then — after
            // the dispatch — the results. Recording them as separate entries is precisely the point of v2.1a.
            notify(List.of(assistant), reply.meta());
            log.info("turn {}: dispatching {} tool call(s): {}", turn, assistant.toolCalls().size(),
                    assistant.toolCalls().stream().map(AgentChatMessage.ToolCall::name).toList());
            List<AgentChatMessage.ToolResult> results = toolDispatcher.dispatchAll(assistant.toolCalls());
            AgentChatMessage toolMsg = AgentChatMessage.toolResults(results);
            messages.add(toolMsg);
            notify(List.of(toolMsg), null);
        }
        throw new MaxTurnsExceeded("agent loop exceeded " + maxTurns + " turns without a final reply");
    }

    private void notify(List<AgentChatMessage> newMessages, LlmMeta meta) {
        observer.onMessages(newMessages, meta);
    }

    private void notifyUsage(LlmUsage usage) {
        if (usage != null) {
            observer.onUsage(usage);
        }
    }

    /** Immutable copy — the loop mutates {@code messages}, the snapshot must be turn-1 state. */
    private void notifyStart(List<AgentChatMessage> messages) {
        observer.onStart(List.copyOf(messages));
    }
}
