package ru.agimate.agentworker.agent;

import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.agent.error.EmptyAnswerExhausted;
import ru.agimate.agentworker.agent.error.LlmCallError;
import ru.agimate.agentworker.agent.error.LlmResponseIncomplete;
import ru.agimate.agentworker.agent.error.MaxTurnsExceeded;
import ru.agimate.agentworker.agent.error.RunCancelled;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.LlmMeta;
import ru.agimate.agentworker.agent.model.LlmUsage;
import ru.agimate.agentworker.agent.model.ToolDef;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Minimal agent turn-loop over {@link AgentChatMessage}. Drives a model conversation manually so
 * the LLM call and tool calls can be dispatched on separate DBOS queues. The agent knows nothing
 * about DBOS, credentials, transport, or history — the LLM call and the tool dispatcher are
 * injected, and the initial message list is built by the caller.
 *
 * <p>Each turn asks the model, {@link #classify classifies} the reply and acts on the verdict. A
 * tool turn notifies twice — the calls before the dispatch, the results after — so the backend can
 * show a call the moment it is made, ahead of its possibly slow execution.
 *
 * <p>Four policies keep a degenerate turn from passing for an answer, each documented where it
 * lives: cancellation (two cooperative checks below), steering ({@link #absorbSteering}), the turn
 * budget and its soft landing ({@link TurnBudget}), and the empty-reply guard. The last exists
 * because reasoning models behind OpenAI-compatible gateways can spend the whole generation on
 * {@code reasoning_content} and return an empty {@code content} with {@code finish_reason: stop};
 * nothing marks that as a failure, so without the guard a run ends «successfully» into silence.
 */
@Slf4j
public class AgiMateAgent {

    /** One, not several: a re-ask usually clears the hiccup, and each costs a full call the user waits through. */
    static final int MAX_EMPTY_RETRIES = 1;

    /** How many turns before the cap the wrap-up notice is injected; the last turn runs without tools. */
    static final int WRAP_UP_TURNS = 2;

    /**
     * Without a cap a chatty session would keep one run alive — and RUNNING — forever, with nothing
     * but a manual stop to end it. Past it the seam stops polling and queued messages run on their own.
     */
    static final int MAX_STEERING_RESETS = 5;

    /** Injected single-model-request call; throws {@link LlmCallError} on HTTP/API failure. */
    @FunctionalInterface
    public interface LlmCaller {
        LlmReply call(List<AgentChatMessage> messages, List<ToolDef> toolDefs);
    }

    /**
     * How the model ended the turn. Provider dialects are mapped to this in the dispatcher, so the
     * loop never sees a raw {@code finish_reason}. {@code UNKNOWN} covers absent and unrecognised
     * values ({@code end_turn}, {@code eos}, null), where the message's own shape decides instead.
     */
    public enum Completion {TOOL_CALLS, STOP, UNKNOWN}

    /**
     * An LLM reply. {@code meta} is the provenance the turn ledger keeps, {@code usage} the call's
     * tokens ({@code null} when there is nothing to account), {@code incompleteReason} the terminal
     * truncation reason ({@code null} on a normal finish). The loop surfaces {@code usage}
     * <b>before</b> acting on {@code incompleteReason}: a truncated call has already spent them.
     */
    public record LlmReply(AgentChatMessage message, LlmMeta meta, LlmUsage usage,
                           LlmResponseIncomplete.Reason incompleteReason, Completion completion) {

        public LlmReply {
            completion = completion != null ? completion : Completion.UNKNOWN;
        }

        public static LlmReply of(AgentChatMessage message) {
            return new LlmReply(message, null, null, null, Completion.UNKNOWN);
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

    /** Synthetic result for a call stopped before it was made. */
    private static final String CANCELLED_RESULT_JSON = "{\"error\":\"cancelled by the user\"}";

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

        /** Has the user asked this run to stop? The wiring knows from answers it already receives — no extra call. */
        default boolean cancelRequested() {
            return false;
        }

        /**
         * Messages of the session that arrived while the run was working (steering), ready to
         * append to the conversation — framed, oldest first; empty when nothing is pending. Called
         * at the seam only. The implementation owns the whole exchange: claiming, recording the
         * turns, and confirming once the model has seen them.
         */
        default List<AgentChatMessage> pollSteering() {
            return List.of();
        }

        RunObserver NOOP = new RunObserver() {};
    }

    private final LlmCaller llmCaller;
    private final ToolDispatcher toolDispatcher;
    private final List<ToolDef> toolDefs;
    private final int maxTurns;
    private final String wrapUpNotice;
    private final RunObserver observer;

    /** @param wrapUpNotice the soft landing's «finish with what you have», resolved by the caller
     *                      ({@code ResponseTemplates.wrapUp}) — the model reads it, so it follows
     *                      the dialogue's language, not this class */
    public AgiMateAgent(LlmCaller llmCaller, ToolDispatcher toolDispatcher, List<ToolDef> toolDefs,
                       int maxTurns, String wrapUpNotice, RunObserver observer) {
        this.llmCaller = llmCaller;
        this.toolDispatcher = toolDispatcher;
        this.toolDefs = toolDefs;
        this.maxTurns = maxTurns;
        this.wrapUpNotice = wrapUpNotice;
        this.observer = observer != null ? observer : RunObserver.NOOP;
    }

    /**
     * Drive the loop starting from {@code messages} (mutated in place). Returns the final text.
     * Throws {@link MaxTurnsExceeded} if no final reply is produced.
     */
    public String run(List<AgentChatMessage> messages) {
        TurnBudget turnBudget = new TurnBudget(maxTurns);
        int emptyRetries = 0;
        boolean started = false;
        // Tracked so an absorption can pull it back out of the list.
        AgentChatMessage injectedWrapUp = null;
        // Collected as we go rather than scanned off `messages`, which opens with earlier runs' history.
        LinkedHashSet<String> executed = new LinkedHashSet<>();
        while (turnBudget.next()) {
            // The seam: the message list is whole here, so the run can be left loadable. Cancellation
            // goes first — a stop must not absorb new work on its way out.
            if (observer.cancelRequested()) {
                log.info("turn {}: cancelled — stopping at the seam", turnBudget.current());
                throw new RunCancelled(List.copyOf(executed));
            }
            // «Finish now» contradicts fresh work; the re-armed soft landing injects it again if needed.
            if (absorbSteering(messages, turnBudget) && injectedWrapUp != null) {
                messages.remove(injectedWrapUp);
                injectedWrapUp = null;
            }
            // The seam is behind us — the turn's number cannot change for the rest of the body.
            final int turn = turnBudget.current();
            // After the first seam's poll, so messages steered in while the run sat queued land in
            // the snapshot: it must be exactly what the first LLM call sees.
            if (!started) {
                notifyStart(messages);
                started = true;
            }
            if (turnBudget.wrapUpTurn()) {
                // Ephemeral: no notify, so it reaches the model but neither history nor the channel.
                log.info("turn {}/{}: injecting wrap-up notice", turn, turnBudget.max());
                injectedWrapUp = AgentChatMessage.user(wrapUpNotice);
                messages.add(injectedWrapUp);
            }
            boolean toolless = turnBudget.toolless();
            log.info("turn {}/{}: requesting LLM{}", turn, turnBudget.max(), toolless ? " (tool-less final)" : "");
            LlmReply reply = llmCaller.call(messages, toolless ? List.of() : toolDefs);
            // Before any decision: a truncated call has already spent its tokens while the turn is
            // about to break off.
            notifyUsage(reply.usage());
            if (reply.incompleteReason() != null) {
                throw new LlmResponseIncomplete(reply.incompleteReason());
            }
            AgentChatMessage assistant = reply.message();
            messages.add(assistant);

            switch (classify(reply)) {
                case EMPTY -> {
                    if (emptyRetries >= MAX_EMPTY_RETRIES) {
                        log.warn("turn {}: still empty after {} retry(-ies), aborting", turn, MAX_EMPTY_RETRIES);
                        throw new EmptyAnswerExhausted("model returned no text after "
                                + MAX_EMPTY_RETRIES + " retry(-ies)");
                    }
                    emptyRetries++;
                    log.warn("turn {}: empty reply, re-asking ({}/{})",
                            turn, emptyRetries, MAX_EMPTY_RETRIES);
                    // Nothing takes the empty turn's place, so the re-ask is byte-for-byte the request
                    // that produced it — that is the hypothesis: a provider hiccup a re-roll clears.
                    // Keeping the turn is not an option either, strict gateways reject empty content.
                    messages.remove(messages.size() - 1);
                }
                case ANSWER -> {
                    String answer = text(assistant);
                    notify(List.of(assistant), reply.meta());
                    log.info("turn {}: final answer ({} chars)", turn, answer.length());
                    return answer;
                }
                case CALLS_LOST -> {
                    // The turn keeps its text, so the model sees its own attempt and can repeat it as a
                    // structural call; the turn cap ends the run if it never does.
                    log.warn("turn {}: finish_reason says tool calls, none parsed — re-asking", turn);
                    notify(List.of(assistant), reply.meta());
                }
                case DISPATCH -> {
                    // Calls now, results after the dispatch: two separate records is the point of v2.1a.
                    notify(List.of(assistant), reply.meta());
                    // The cheapest place to stop: decided, but nothing sent yet — later it is irreversible.
                    if (observer.cancelRequested()) {
                        log.info("turn {}: cancelled — {} call(s) not made", turn, assistant.toolCalls().size());
                        recordResults(messages, cancelledResults(assistant.toolCalls()));
                        throw new RunCancelled(List.copyOf(executed));
                    }
                    dispatchTools(messages, assistant, executed, turn);
                }
            }
        }
        throw new MaxTurnsExceeded("agent loop exceeded " + maxTurns + " turns without a final reply");
    }

    /**
     * Runs the assistant's calls and appends the single tool-result message they produce, collecting
     * the names that actually returned — the receipt a stop reports. Non-terminal by construction: a
     * failed call comes back as a failed result, so the loop, not this method, decides what ends a run.
     */
    private void dispatchTools(List<AgentChatMessage> messages, AgentChatMessage assistant,
                               Set<String> executed, int turn) {
        log.info("turn {}: dispatching {} tool call(s): {}", turn, assistant.toolCalls().size(),
                assistant.toolCalls().stream().map(AgentChatMessage.ToolCall::name).toList());
        List<AgentChatMessage.ToolResult> results = toolDispatcher.dispatchAll(assistant.toolCalls());
        recordResults(messages, results);
        for (AgentChatMessage.ToolResult result : results) {
            if (!result.failed() && result.name() != null) {
                executed.add(result.name());
            }
        }
    }

    /**
     * The steering half of the seam: messages of the session that arrived while this run was working.
     * Absorbing them resets the turn budget — the new message deserves the full allowance — which
     * also re-arms the soft landing. Never terminal, unlike the cancellation check that precedes it.
     *
     * @return whether anything was absorbed
     */
    private boolean absorbSteering(List<AgentChatMessage> messages, TurnBudget budget) {
        if (!budget.canSteer()) {
            return false;
        }
        List<AgentChatMessage> absorbed = observer.pollSteering();
        if (absorbed.isEmpty()) {
            return false;
        }
        int at = budget.current();
        budget.reset();
        messages.addAll(absorbed);
        log.info("turn {}: absorbed {} steered message(s), turn budget reset ({}/{})",
                at, absorbed.size(), budget.resets(), MAX_STEERING_RESETS);
        return true;
    }

    /** What the loop does with a reply — see {@link #classify}. */
    enum Verdict {
        /** No text and no calls: not a final answer, whatever {@code finish_reason} claims. */
        EMPTY,
        /** The turn is done and its text is the run's answer. */
        ANSWER,
        /** The model went for a tool and the call was lost — a gateway dropped it, arguments failed to parse. */
        CALLS_LOST,
        /** The assistant's calls are ready to dispatch. */
        DISPATCH
    }

    /**
     * The whole {@code finish_reason} × tool-calls × text table in one place. The order is the policy,
     * not an accident: EMPTY comes first, so a «tool calls» finish whose calls did not survive the
     * gateway and left no text lands there rather than being re-sent as empty assistant content,
     * which strict gateways reject.
     */
    static Verdict classify(LlmReply reply) {
        boolean hasCalls = reply.message().hasToolCalls();
        if (!hasCalls && text(reply.message()).isBlank()) {
            return Verdict.EMPTY;
        }
        if (!continues(reply)) {
            return Verdict.ANSWER;
        }
        return hasCalls ? Verdict.DISPATCH : Verdict.CALLS_LOST;
    }

    private static String text(AgentChatMessage message) {
        return message.text() != null ? message.text() : "";
    }

    /**
     * Does the turn continue? The provider's {@code finish_reason} decides: {@code TOOL_CALLS} — the
     * model is mid-work, {@code STOP} — it is done and the message is the answer. Only when the
     * provider said nothing recognisable does the message's own shape decide.
     */
    private static boolean continues(LlmReply reply) {
        return switch (reply.completion()) {
            case TOOL_CALLS -> true;
            case STOP -> false;
            case UNKNOWN -> reply.message().hasToolCalls();
        };
    }

    private void recordResults(List<AgentChatMessage> messages, List<AgentChatMessage.ToolResult> results) {
        AgentChatMessage toolMsg = AgentChatMessage.toolResults(results);
        messages.add(toolMsg);
        notify(List.of(toolMsg), null);
    }

    /** Answers every call without making it: a {@code tool_use} with no {@code tool_result} is rejected next run. */
    private static List<AgentChatMessage.ToolResult> cancelledResults(List<AgentChatMessage.ToolCall> calls) {
        return calls.stream()
                .map(c -> new AgentChatMessage.ToolResult(c.id(), c.name(), CANCELLED_RESULT_JSON, true))
                .toList();
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
