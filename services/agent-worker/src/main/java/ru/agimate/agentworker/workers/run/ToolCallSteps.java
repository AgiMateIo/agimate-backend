package ru.agimate.agentworker.workers.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.DetachToolResponse;
import ru.agimate.agentworker.GetToolResultResponse;
import ru.agimate.agentworker.ToolResultStatus;
import ru.agimate.agentworker.config.AgentProperties;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The bodies of the run workflow's three tool steps: issue every call of the turn, observe the
 * waiting ones when the workflow wakes, and detach those whose wait is over. Nothing here waits —
 * the {@code recv} that decides when to look again lives in {@link ToolCallDispatcher}, in the
 * workflow body, because DBOS forbids it inside a step.
 *
 * <p>A call ends in one of two ways: it settles (SUCCESS, ERROR, or the user's stop), or it is
 * detached ({@code DetachTool}) and the model gets an interim task handle while the backend
 * delivers the result later as a {@code tool_completed} trigger. Detaching happens when a new
 * input has arrived in the session after {@code agent.tool.detach-after} — the next turn will
 * absorb that input anyway, so there is nothing to gain by making the model wait — or at the
 * ceiling {@code agent.tool.max-wait}, because a run holds its session's queue partition. A failed
 * detach falls back to blocking until the budget, which then ends as {@link Status#TIMEOUT}.
 *
 * <p>Every decision about time is taken here and returned as data, so a replay branches only on
 * recorded results: the step bodies decide a call's fate, {@link #nextWakeMs} decides when the
 * workflow looks again, and the dispatcher only orchestrates the three. The checkpoint
 * ({@link Outcomes}) is ids and statuses: outputs and backend error texts stay in run memory
 * ({@code held}), and a crash replay re-reads them by id.
 */
@Slf4j
public class ToolCallSteps {

    /** Ceiling on the budget declared by a spec — 30 minutes. */
    static final int MAX_TIMEOUT_SECONDS = 1800;

    /**
     * Longest silence between two looks at the pending calls: the safety net for a lost message, and
     * the run's periodic sign of life. Must stay below control-api's stale-run threshold (15 min).
     */
    static final long RECV_TIMEOUT_MS = 60_000;

    /** Floor on a single {@code recv}: waking for a decision already due must not become a spin. */
    private static final long RECV_MIN_MS = 10;

    /** One call to issue; {@code timeoutSeconds} ≤ 0 means the worker's default budget. */
    public record Call(String toolCallId, String connectorCode, String connectionId, String toolName,
                       String argsJson, int timeoutSeconds) {}

    public enum Status {
        /** Output on the backend and in {@code held}. */
        SUCCESS,
        /** The tool failed; its error text is on the backend and in {@code held}. */
        ERROR,
        /** Handed to the backend; the interim is regenerated from the id. */
        DETACHED,
        /** The budget ran out and detaching was impossible; the notice is regenerated from the name and budget. */
        TIMEOUT,
        /** The user stopped the run; the notice is regenerated from the name. */
        ABANDONED,
        /** The worker could not issue or follow the call; {@code error} carries the reason. */
        FAILED
    }

    /** @param error only for {@link Status#FAILED}; {@code null} otherwise */
    public record Outcome(String toolCallId, Status status, String error) {}

    /** Wrapped rather than a bare list so the checkpoint deserializes to a known type. */
    public record Outcomes(List<Outcome> items) {
        Outcome of(String toolCallId) {
            return items.stream().filter(o -> o.toolCallId().equals(toolCallId)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("no outcome for tool call " + toolCallId));
        }
    }

    /**
     * A call the backend accepted, with the instants of its wait fixed at issue time — a replay
     * reads the same schedule even if the config changed under it. {@code Long.MAX_VALUE} means
     * "never": the threshold and the ceiling are gone when detaching is disabled.
     */
    public record Issued(String toolCallId, long detachAtMs, long waitEndAtMs, long deadlineAtMs) {}

    /** The {@code tool_issue} checkpoint: what the backend took, and what it refused outright. */
    public record IssuedBatch(List<Outcome> rejected, List<Issued> accepted) {}

    /** The {@code tool_observe} checkpoint: what settled, what is still running, what must be detached. */
    public record Observation(List<Outcome> settled, List<Issued> pending, List<Issued> toDetach) {}

    /**
     * What the run knows about the wait itself, as opposed to the calls in it: whether a new input
     * arrived in the session, and which calls already failed to detach.
     */
    public record WaitState(boolean incoming, Set<String> undetachable) {}

    /** The {@code tool_detach} checkpoint: what the race settled, and which calls stayed undetachable. */
    public record DetachResult(List<Outcome> settled, List<String> undetachable) {}

    private final AgentWorkerClient client;
    private final long pollTimeoutMs;
    private final long detachAfterMs;
    private final long maxWaitMs;
    private final int maxOutputChars;

    public ToolCallSteps(AgentWorkerClient client, AgentProperties.Tool tool) {
        this.client = client;
        this.pollTimeoutMs = tool.getPollTimeout().toMillis();
        this.detachAfterMs = tool.getDetachAfter().toMillis();
        this.maxWaitMs = tool.getMaxWait().toMillis();
        this.maxOutputChars = tool.getMaxOutputChars();
    }

    int maxOutputChars() {
        return maxOutputChars;
    }

    /**
     * Hand every call of the turn to the backend. The backend executes them concurrently from this
     * moment; the worker only waits. A call the backend refused is settled here, alone.
     */
    public IssuedBatch issue(List<Call> calls, String agentId, String runId) {
        long now = System.currentTimeMillis();
        List<Issued> accepted = new ArrayList<>(calls.size());
        List<Outcome> rejected = new ArrayList<>();
        for (Call call : calls) {
            try {
                client.executeToolAsync(call.toolCallId(), call.connectorCode(), call.connectionId(),
                        call.toolName(), call.argsJson().getBytes(StandardCharsets.UTF_8), agentId, runId);
            } catch (Exception e) {
                log.warn("tool {} (connector={}) could not be issued: {}", call.toolName(), call.connectorCode(), e.getMessage());
                rejected.add(new Outcome(call.toolCallId(), Status.FAILED, Failures.message(e)));
                continue;
            }
            accepted.add(schedule(call, now));
        }
        return new IssuedBatch(rejected, accepted);
    }

    /** When this call's wait reaches each of its three instants; computed once, at issue. */
    private Issued schedule(Call call, long now) {
        long budgetMs = effectiveTimeoutMs(call.timeoutSeconds(), pollTimeoutMs);
        long deadline = now + budgetMs;
        if (detachAfterMs <= 0) {
            // Detaching is switched off: the budget alone bounds the wait, as before the feature.
            return new Issued(call.toolCallId(), Long.MAX_VALUE, Long.MAX_VALUE, deadline);
        }
        long waitEnd = maxWaitMs > 0 ? now + Math.min(budgetMs, maxWaitMs) : deadline;
        return new Issued(call.toolCallId(), now + Math.min(detachAfterMs, budgetMs), waitEnd, deadline);
    }

    /**
     * One look at the pending calls; never throws. {@code held} receives each SUCCESS output and
     * ERROR text by id — the run-memory copy the dispatcher reads instead of the backend.
     */
    public Observation observe(List<Issued> pending, String agentId, String runId,
                               Map<String, String> held, WaitState wait) {
        long now = System.currentTimeMillis();
        List<Outcome> settled = new ArrayList<>();
        List<Issued> stillPending = new ArrayList<>(pending.size());
        List<Issued> toDetach = new ArrayList<>();
        for (Issued p : pending) {
            String id = p.toolCallId();
            Outcome outcome;
            try {
                outcome = pollOnce(id, agentId, runId, held);
            } catch (Exception e) {
                log.warn("tool call {} could not be followed: {}", id, e.getMessage());
                settled.add(new Outcome(id, Status.FAILED, Failures.message(e)));
                continue;
            }
            if (outcome != null) {
                settled.add(outcome);
                continue;
            }
            boolean detachable = detachAfterMs > 0 && !wait.undetachable().contains(id);
            if (detachable && (now >= p.waitEndAtMs() || (wait.incoming() && now >= p.detachAtMs()))) {
                toDetach.add(p);
                continue;
            }
            // Only reachable with detaching off or after a failed detach: the wait ends without
            // the result, and the backend job is not cancelled.
            if (now >= p.deadlineAtMs()) {
                settled.add(new Outcome(id, Status.TIMEOUT, null));
                continue;
            }
            stillPending.add(p);
        }
        return new Observation(settled, stillPending, toDetach);
    }

    /**
     * Detach the calls whose wait is over. {@code DetachTool} settles the race: a call that
     * finished meanwhile comes back as its plain result, and one whose detach failed — an old
     * backend, a network hiccup — is reported as undetachable and blocks until its budget.
     */
    public DetachResult detach(List<Issued> calls, String agentId, String runId, Map<String, String> held) {
        List<Outcome> settled = new ArrayList<>(calls.size());
        List<String> undetachable = new ArrayList<>();
        for (Issued p : calls) {
            String id = p.toolCallId();
            DetachToolResponse detach = null;
            try {
                detach = client.detachTool(agentId, id, runId);
            } catch (Exception e) {
                log.warn("detach of tool call {} failed, falling back to blocking: {}", id, e.getMessage());
            }
            if (detach == null) {
                undetachable.add(id);
                continue;
            }
            switch (detach.getStatus()) {
                case TOOL_RESULT_STATUS_DETACHED -> settled.add(new Outcome(id, Status.DETACHED, null));
                case TOOL_RESULT_STATUS_SUCCESS -> {
                    held.put(id, detach.getOutputJson().toStringUtf8());
                    settled.add(new Outcome(id, Status.SUCCESS, null));
                }
                case TOOL_RESULT_STATUS_ERROR -> {
                    held.put(id, detach.getError());
                    settled.add(new Outcome(id, Status.ERROR, null));
                }
                default -> undetachable.add(id);
            }
        }
        return new DetachResult(settled, undetachable);
    }

    /** One poll of a call; {@code null} while it is still running. */
    private Outcome pollOnce(String id, String agentId, String runId, Map<String, String> held) {
        GetToolResultResponse result = client.getToolResult(agentId, id, runId);
        switch (result.getStatus()) {
            case TOOL_RESULT_STATUS_SUCCESS -> {
                held.put(id, result.getOutputJson().toStringUtf8());
                return new Outcome(id, Status.SUCCESS, null);
            }
            case TOOL_RESULT_STATUS_ERROR -> {
                held.put(id, result.getError());
                return new Outcome(id, Status.ERROR, null);
            }
            // A replay of a seam that already detached: same interim, same outcome.
            case TOOL_RESULT_STATUS_DETACHED -> {
                return new Outcome(id, Status.DETACHED, null);
            }
            // Only the wait ends — the call keeps running, hence «may still complete» in the notice.
            case TOOL_RESULT_STATUS_CANCELLED -> {
                return new Outcome(id, Status.ABANDONED, null);
            }
            default -> { }
        }
        return null;
    }

    /**
     * Milliseconds to block before the next look: the distance to the nearest decision, never past
     * the reconcile tick, never below the floor. Pure over the recorded schedule, so a replay reads
     * the same rule; the clock only turns instants into a duration.
     */
    static long nextWakeMs(List<Issued> waiting, WaitState wait) {
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        for (Issued p : waiting) {
            next = Math.min(next, p.deadlineAtMs());
            // A call whose detach already failed has only the budget left — its threshold and
            // ceiling are spent, and waking for them would end in another useless attempt.
            if (!wait.undetachable().contains(p.toolCallId())) {
                next = Math.min(next, p.waitEndAtMs());
                if (wait.incoming()) {
                    next = Math.min(next, p.detachAtMs());
                }
            }
        }
        if (next == Long.MAX_VALUE) {
            // Unreachable today — every pending call has a budget — but this guard is what keeps a
            // future "never" instant from turning the subtraction below into a busy loop.
            return RECV_TIMEOUT_MS;
        }
        return Math.max(RECV_MIN_MS, Math.min(RECV_TIMEOUT_MS, next - now));
    }

    /** Wait budget: the one declared by the spec (clamped to 30 min), or the worker's default. */
    static long effectiveTimeoutMs(int specTimeoutSeconds, long defaultMs) {
        if (specTimeoutSeconds <= 0) {
            return defaultMs;
        }
        return Math.min(specTimeoutSeconds, MAX_TIMEOUT_SECONDS) * 1000L;
    }

    /** The budget the model is told about in a timeout notice — the same arithmetic, so a replay says the same. */
    long budgetSeconds(int specTimeoutSeconds) {
        return effectiveTimeoutMs(specTimeoutSeconds, pollTimeoutMs) / 1000;
    }

    static String timeoutNotice(String toolName, String toolCallId, long budgetSeconds) {
        return "tool " + toolName + " (id=" + toolCallId + ") did not finish within " + budgetSeconds
                + "s; the call was NOT cancelled and may still complete with its effects applied — verify the"
                + " current state before retrying";
    }

    static String abandonedNotice(String toolName, String toolCallId) {
        return "tool " + toolName + " (id=" + toolCallId + ") was abandoned: the user stopped the run."
                + " The call was NOT cancelled and may still complete with its effects applied";
    }

    static String errorNotice(String toolName, String error) {
        return "tool " + toolName + " failed: " + (error == null || error.isBlank() ? "no error message" : error);
    }

    /**
     * The interim handed to the model instead of a detached call's result. Valid JSON built by
     * Jackson; the wording must keep the model from re-invoking the tool or inventing the result —
     * and the {@code task_id} is what the later {@code tool_completed} message will reference.
     */
    static String detachedInterim(String toolCallId) {
        try {
            return new ObjectMapper().writeValueAsString(Map.of(
                    "status", "detached",
                    "task_id", toolCallId,
                    "note", "The tool is still running in the background. Its result will arrive"
                            + " later as a separate incoming message referencing this task_id,"
                            + " possibly after the current run has finished. Do not call the tool"
                            + " again and do not invent its result; when finishing your answer, tell"
                            + " the user the work continues and you will report the outcome."));
        } catch (JsonProcessingException e) {
            // Unreachable for a map of constants; keep the contract of never raising.
            return "{\"status\":\"detached\",\"task_id\":\"" + toolCallId + "\"}";
        }
    }

    /**
     * Cut a giant tool output down to {@code maxChars} with an explicit marker: the output rides
     * in the model context of every following turn, so an unbounded one (a wide SELECT, a dumped
     * file) inflates the whole rest of the run. The cut result is no longer valid JSON; the model
     * reads it as text, the marker says what happened.
     */
    static String truncateOutput(String output, int maxChars) {
        if (output.length() <= maxChars) {
            return output;
        }
        int cut = maxChars;
        // Do not tear a UTF-16 surrogate pair in half.
        if (Character.isHighSurrogate(output.charAt(cut - 1))) {
            cut--;
        }
        return output.substring(0, cut)
                + "\n…[tool output truncated by worker: " + output.length()
                + " chars total, first " + cut + " shown]";
    }
}
