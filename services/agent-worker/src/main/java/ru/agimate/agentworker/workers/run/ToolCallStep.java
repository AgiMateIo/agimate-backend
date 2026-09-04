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

/**
 * The body of the run workflow's {@code tool_calls} step: issue {@code ExecuteToolAsync} for every
 * call of the turn, then poll them round-robin until each is settled. The backend executes the
 * tools concurrently from the moment they are issued; the worker only waits, so one loop is as
 * parallel as one workflow per call used to be.
 *
 * <p>The checkpoint ({@link Outcomes}) is ids and statuses. Outputs and backend error texts stay in
 * run memory ({@code held}); a crash replay re-reads them from the backend by id
 * ({@code GetToolResult}), and the other outcomes are regenerated from the id alone. Only a
 * worker-side failure ({@link Status#FAILED}: the RPC itself was rejected) carries its text — it is
 * on no backend row to read back.
 *
 * <p>A call still pending at {@code agent.tool.detach-after} is detached ({@code DetachTool}): the
 * model gets an interim task handle and the backend delivers the result later as a
 * {@code tool_completed} trigger. Detaching flips the result's ownership visibly — once detached,
 * {@code GetToolResult} answers DETACHED forever, so a replay records the same interim. A failed
 * detach falls back to blocking, bounded by the call's poll budget: the spec's
 * {@code timeout_seconds} (clamped to {@value #MAX_TIMEOUT_SECONDS}s) when declared, otherwise
 * {@code agent.tool.poll-timeout}. The budget bounds waiting only — it does not cancel the backend
 * job, so a timed-out tool may still complete and apply its effects.
 */
@Slf4j
public class ToolCallStep {

    private static final long POLL_INTERVAL_MS = 500;
    /** After the first minute of waiting we poll less often: long tools do not deserve 2 rps of gRPC. */
    private static final long SLOW_POLL_INTERVAL_MS = 2_000;
    private static final long SLOW_POLL_AFTER_MS = 60_000;
    /** Ceiling on the budget declared by a spec — 30 minutes. */
    static final int MAX_TIMEOUT_SECONDS = 1800;

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
        /** The poll budget ran out; the notice is regenerated from the name and budget. */
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

    private final AgentWorkerClient client;
    private final long pollTimeoutMs;
    private final long detachAfterMs;
    private final int maxOutputChars;

    public ToolCallStep(AgentWorkerClient client, AgentProperties.Tool tool) {
        this.client = client;
        this.pollTimeoutMs = tool.getPollTimeout().toMillis();
        this.detachAfterMs = tool.getDetachAfter().toMillis();
        this.maxOutputChars = tool.getMaxOutputChars();
    }

    int maxOutputChars() {
        return maxOutputChars;
    }

    /**
     * Issue and await every call; never throws. {@code held} receives each SUCCESS output and ERROR
     * text by id — the run-memory copy the dispatcher reads instead of the backend.
     */
    public Outcomes run(List<Call> calls, String agentId, String runId, Map<String, String> held) {
        long start = System.currentTimeMillis();
        List<Pending> pending = new ArrayList<>(calls.size());
        List<Outcome> settled = new ArrayList<>(calls.size());
        for (Call call : calls) {
            try {
                client.executeToolAsync(call.toolCallId(), call.connectorCode(), call.connectionId(),
                        call.toolName(), call.argsJson().getBytes(StandardCharsets.UTF_8), agentId, runId);
            } catch (Exception e) {
                log.warn("tool {} (connector={}) could not be issued: {}", call.toolName(), call.connectorCode(), e.getMessage());
                settled.add(new Outcome(call.toolCallId(), Status.FAILED, nonBlankMessage(e)));
                continue;
            }
            long budgetMs = effectiveTimeoutMs(call.timeoutSeconds(), pollTimeoutMs);
            // The detach attempt never waits past the budget: a spec that declared a tighter timeout
            // gets its call detached at that timeout, not at the worker's default.
            long detachAt = detachAfterMs > 0 ? start + Math.min(detachAfterMs, budgetMs) : Long.MAX_VALUE;
            pending.add(new Pending(call, start + budgetMs, detachAt));
        }

        while (!pending.isEmpty()) {
            for (var it = pending.iterator(); it.hasNext(); ) {
                Pending p = it.next();
                Outcome outcome;
                try {
                    outcome = pollOnce(p, agentId, runId, held);
                } catch (Exception e) {
                    log.warn("tool {} (id={}) could not be followed: {}", p.call.toolName(), p.call.toolCallId(), e.getMessage());
                    outcome = new Outcome(p.call.toolCallId(), Status.FAILED, nonBlankMessage(e));
                }
                if (outcome != null) {
                    settled.add(outcome);
                    it.remove();
                }
            }
            if (pending.isEmpty()) {
                break;
            }
            try {
                Thread.sleep(System.currentTimeMillis() - start < SLOW_POLL_AFTER_MS ? POLL_INTERVAL_MS : SLOW_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                for (Pending p : pending) {
                    settled.add(new Outcome(p.call.toolCallId(), Status.FAILED, "interrupted while polling tool " + p.call.toolName()));
                }
                break;
            }
        }
        // Checkpoint order = call order: the dispatcher looks outcomes up by id, but a stable shape is easier to read.
        List<Outcome> ordered = new ArrayList<>(calls.size());
        for (Call call : calls) {
            settled.stream().filter(o -> o.toolCallId().equals(call.toolCallId())).findFirst().ifPresent(ordered::add);
        }
        return new Outcomes(ordered);
    }

    /** One poll of a pending call; {@code null} while it is still running. */
    private Outcome pollOnce(Pending p, String agentId, String runId, Map<String, String> held) {
        String id = p.call.toolCallId();
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
        long now = System.currentTimeMillis();
        if (now >= p.detachAt && !p.detachFailed) {
            DetachToolResponse detach = null;
            try {
                detach = client.detachTool(agentId, id, runId);
            } catch (Exception e) {
                // Best-effort by design (an old backend, a network hiccup): fall back to blocking
                // until the budget — slow, never lost.
                log.warn("detach of tool {} (id={}) failed, falling back to blocking: {}",
                        p.call.toolName(), id, e.getMessage());
            }
            if (detach == null || detach.getStatus() == ToolResultStatus.TOOL_RESULT_STATUS_UNSPECIFIED) {
                p.detachFailed = true;
            } else if (detach.getStatus() == ToolResultStatus.TOOL_RESULT_STATUS_DETACHED) {
                return new Outcome(id, Status.DETACHED, null);
            } else if (detach.getStatus() == ToolResultStatus.TOOL_RESULT_STATUS_ERROR) {
                held.put(id, detach.getError());
                return new Outcome(id, Status.ERROR, null);
            } else {
                // SUCCESS: the call finished while we were detaching — the plain result won the race.
                held.put(id, detach.getOutputJson().toStringUtf8());
                return new Outcome(id, Status.SUCCESS, null);
            }
        }
        if (now > p.deadline) {
            return new Outcome(id, Status.TIMEOUT, null);
        }
        return null;
    }

    private static final class Pending {
        final Call call;
        final long deadline;
        final long detachAt;
        boolean detachFailed;

        Pending(Call call, long deadline, long detachAt) {
            this.call = call;
            this.deadline = deadline;
            this.detachAt = detachAt;
        }
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

    private static String nonBlankMessage(Throwable t) {
        String msg = t.getMessage();
        return msg != null && !msg.isBlank() ? msg : t.getClass().getSimpleName();
    }
}
