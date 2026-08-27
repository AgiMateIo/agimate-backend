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
 * <p>Cancellation is cooperative and checked twice: at the seam and again just before a dispatch, so
 * a stop does not spend tool calls the user no longer wants. Calls already made are never interrupted;
 * unmade ones get a synthetic result, since a {@code tool_use} with no {@code tool_result} is rejected
 * by providers on the next run.
 *
 * <p>Steering is polled at the seam only (after the cancel check — a stop wins): messages of the
 * session that arrived mid-run are appended to the conversation instead of waiting out the queue,
 * the turn budget resets and the wrap-up notice, if already injected, is withdrawn — new work and
 * «finish now» must not coexist. Resets are capped at {@value #MAX_STEERING_RESETS} per run; the
 * dispatch seam does not steer — a {@code tool_use} must be followed by its results, a user message
 * cannot sit between them.
 *
 * <p>Guard: a tool-less turn with empty text is not a final answer. Reasoning models behind
 * OpenAI-compatible gateways sometimes spend the whole generation on {@code reasoning_content} and
 * return an empty {@code content} with {@code finish_reason: stop} — nothing marks it as a failure,
 * so without the guard the run ends «successfully» and the user sees silence. The empty turn is
 * dropped from the conversation, a nudge is appended and the model is asked again (up to
 * {@value #MAX_EMPTY_RETRIES} time per run); if it stays empty the run aborts with
 * {@link EmptyAnswerExhausted} and the user gets a notice.
 *
 * <p>Soft landing at the cap: {@value #WRAP_UP_TURNS} turns before the limit an ephemeral wrap-up
 * notice is injected («finish with what you have»), and the last turn runs <b>without tools</b> —
 * forcing the model to produce final text. Iterative perfectionism (generate → check → «not quite»
 * → again) then ends in a degraded but useful answer with the artefacts already produced, rather
 * than in {@link MaxTurnsExceeded} with all the work lost. {@code MaxTurnsExceeded} remains
 * possible only if the model fails to answer even on the tool-less turn.
 */
@Slf4j
public class AgiMateAgent {

    /**
     * One retry, not several: an empty reply is a provider hiccup that a single re-ask usually
     * clears, and every attempt costs a full model call the user waits through.
     */
    static final int MAX_EMPTY_RETRIES = 1;

    static final String EMPTY_ANSWER_NUDGE =
            "Предыдущий ход вернулся пустым — пользователь не получил ничего. Ответь обычным "
            + "текстом (поле content), коротко и по существу задачи. Если для ответа нужен "
            + "инструмент — сделай настоящий структурный tool call.";

    /** How many turns before the cap the wrap-up notice is injected; the last turn runs without tools. */
    static final int WRAP_UP_TURNS = 2;

    /**
     * How many times an absorption may reset the turn budget. Each reset also re-arms the soft
     * landing, so without a cap a busy session would keep one run alive — and RUNNING — forever,
     * with nothing but a manual stop to end it. Past the cap the seam stops polling; queued
     * messages simply run on their own after this run finishes.
     */
    static final int MAX_STEERING_RESETS = 5;

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
     * How the model ended the turn, as the provider reported it — the loop's stop/continue signal.
     * The raw {@code finish_reason} is mapped in the dispatcher (provider dialects stay there):
     * {@code TOOL_CALLS} means the model is mid-work and the loop goes on, {@code STOP} means it is
     * done. {@code UNKNOWN} covers an absent or unrecognised value — providers do send
     * {@code end_turn}, {@code eos} and plain nulls — and there the structural fact decides, as it
     * did before: a message carrying tool calls continues the loop, one without them is the answer.
     */
    public enum Completion {TOOL_CALLS, STOP, UNKNOWN}

    /**
     * An LLM reply: the assistant message plus its provenance ({@code meta}) for the turn ledger,
     * the call's token {@code usage} ({@code null} when nothing to account), the {@code completion}
     * that decides whether the loop goes on, and — for a truncated call — the terminal
     * {@code incompleteReason} ({@code null} on a normal finish). The loop surfaces {@code usage}
     * <b>before</b> acting on {@code incompleteReason}, so a truncated call's tokens are still
     * accounted although the turn aborts.
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
    private final RunObserver observer;

    public AgiMateAgent(LlmCaller llmCaller, ToolDispatcher toolDispatcher, List<ToolDef> toolDefs,
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
        int emptyRetries = 0;
        int steeringResets = 0;
        boolean started = false;
        // The injected wrap-up notice, tracked so an absorption can pull it back out of the list.
        AgentChatMessage wrapUpNotice = null;
        // Tools of *this* run that returned a result — the receipt for a stop. Collected as we go
        // rather than scanned off `messages`: that list opens with the history of earlier runs.
        LinkedHashSet<String> executed = new LinkedHashSet<>();
        // Soft landing only for a meaningful cap — a tiny maxTurns (tests, debugging) is left alone.
        boolean softLanding = maxTurns > WRAP_UP_TURNS;
        for (int turn = 1; turn <= maxTurns; turn++) {
            // The seam: the message list is whole here, so the run can be left loadable.
            // Cancellation is checked first — a stop must not absorb new work on its way out.
            if (observer.cancelRequested()) {
                log.info("turn {}: cancelled — stopping at the seam", turn);
                throw new RunCancelled(List.copyOf(executed));
            }
            // Steering: messages that arrived in the session while this run was working. Absorbing
            // one resets the turn budget — the new message deserves the full allowance — and re-arms
            // the soft landing, so the wrap-up notice (which contradicts fresh work) is pulled out
            // and re-injected later if needed. The reset cap keeps a chatty session from making the
            // run immortal: past it, messages stay queued and run on their own.
            if (steeringResets < MAX_STEERING_RESETS) {
                List<AgentChatMessage> absorbed = observer.pollSteering();
                if (!absorbed.isEmpty()) {
                    steeringResets++;
                    messages.addAll(absorbed);
                    if (wrapUpNotice != null) {
                        messages.remove(wrapUpNotice);
                        wrapUpNotice = null;
                    }
                    log.info("turn {}: absorbed {} steered message(s), turn budget reset ({}/{})",
                            turn, absorbed.size(), steeringResets, MAX_STEERING_RESETS);
                    turn = 1;
                }
            }
            // After the first seam's poll, so messages steered in while the run sat queued land in
            // the snapshot: it must be exactly what the first LLM call sees.
            if (!started) {
                notifyStart(messages);
                started = true;
            }
            if (softLanding && turn == maxTurns - WRAP_UP_TURNS + 1) {
                // An ephemeral turn (no notify): it is projected neither into history nor into the channel, like an imitation correction.
                log.info("turn {}/{}: injecting wrap-up notice", turn, maxTurns);
                wrapUpNotice = AgentChatMessage.user(WRAP_UP_NOTICE);
                messages.add(wrapUpNotice);
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
                    // The empty turn is dropped rather than kept: it carries no signal for the model.
                    messages.remove(messages.size() - 1);
                    messages.add(AgentChatMessage.user(EMPTY_ANSWER_NUDGE));
                }
                case ANSWER -> {
                    String answer = text(assistant);
                    notify(List.of(assistant), reply.meta());
                    log.info("turn {}: final answer ({} chars)", turn, answer.length());
                    return answer;
                }
                case CALLS_LOST -> {
                    // We ask again and let the turn cap end it, rather than passing off half a turn as
                    // the result. The turn keeps its text (an empty one is EMPTY, not CALLS_LOST), so the
                    // model sees its own attempt and can repeat it as a structural call.
                    log.warn("turn {}: finish_reason says tool calls, none parsed — re-asking", turn);
                    notify(List.of(assistant), reply.meta());
                }
                case DISPATCH -> {
                    // Two turn events: first the calls (delivered into the channel before execution), then — after
                    // the dispatch — the results. Recording them as separate entries is precisely the point of v2.1a.
                    notify(List.of(assistant), reply.meta());
                    // The cheapest place to stop: the model has decided to call, but nothing has been sent yet.
                    // Later the effect is out in the world and irreversible. Kept in the loop body on
                    // purpose — every exit of a run must be visible where the run is driven.
                    if (observer.cancelRequested()) {
                        log.info("turn {}: cancelled — {} call(s) not made", turn, assistant.toolCalls().size());
                        recordResults(messages, cancelledResults(assistant.toolCalls()));
                        throw new RunCancelled(List.copyOf(executed));
                    }
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
            }
        }
        throw new MaxTurnsExceeded("agent loop exceeded " + maxTurns + " turns without a final reply");
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
     * The whole {@code finish_reason} × tool-calls × text table in one place. The order of the checks
     * is the policy, not an accident: EMPTY is decided first, so a «tool calls» finish whose calls did
     * not survive the gateway and left no text lands there — re-sending empty assistant content with
     * no tool calls is exactly what strict gateways reject. Only then does the provider's
     * stop/continue signal decide, and only for a continuing turn does the message's own shape
     * separate a real dispatch from a lost call.
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

    /** Answers every call without making it — the pair the next run needs, minus the side effects. */
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
