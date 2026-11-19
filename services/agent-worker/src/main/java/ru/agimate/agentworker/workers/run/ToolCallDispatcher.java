package ru.agimate.agentworker.workers.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dbos.transact.DBOS;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.GetToolResultResponse;
import ru.agimate.agentworker.ToolResultStatus;
import ru.agimate.agentworker.WorkerProtocol;
import ru.agimate.agentworker.agent.AgiMateAgent;
import ru.agimate.agentworker.agent.ContextDelta;
import ru.agimate.agentworker.agent.ToolRegistry;
import ru.agimate.agentworker.agent.context.ContextBuilder;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.ContextMaterial;
import ru.agimate.agentworker.grpc.AgentWorkerClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Per-run {@link AgiMateAgent.ToolDispatcher}: the turn's calls go through three durable steps
 * ({@link ToolCallSteps}) and, between them, a {@code recv} on the run's event topic. The wait is
 * event-driven: control-api sends a message the moment a tool result lands or an input arrives in
 * the session, and an empty {@code recv} once a minute is only the reconciliation pass — it re-reads
 * the results, which is also the run's periodic sign of life for control-api's stale-run sweeper.
 *
 * <p>The loop lives here, in the workflow body, because {@code DBOS.recv} is forbidden inside a
 * step. Its control flow branches only on step results and received messages, never on the clock,
 * so a replay walks the same steps in the same order. The contents come from run memory in the
 * normal path and from the backend by id on a crash replay; the worker-side notices (timeout,
 * abandoned, detached) are regenerated. Never throws for a tool failure — a failed call comes back
 * as a failed {@link AgentChatMessage.ToolResult}.
 *
 * <p>A result marked as a context delta ({@link ContextMaterial#TOOLS}) is applied to the registry
 * on the way through: the specs go into the tool list, the model reads the names. Parsed from the
 * raw output, before the truncation cap — a delta of eighty schemas is bigger than the cap — and
 * on a replay from the re-read output, so the registry ends up the same.
 */
@Slf4j
class ToolCallDispatcher implements AgiMateAgent.ToolDispatcher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DBOS dbos;
    private final ToolCallSteps step;
    private final AgentWorkerClient client;
    private final String agentId;
    private final String runId;
    private final ToolRegistry registry;
    /** Whether the run already took that message into the conversation — its event is stale then. */
    private final Predicate<String> absorbedSteering;

    ToolCallDispatcher(DBOS dbos, ToolCallSteps step, AgentWorkerClient client, String agentId, String runId,
                       ToolRegistry registry, Predicate<String> absorbedSteering) {
        this.dbos = dbos;
        this.step = step;
        this.client = client;
        this.agentId = agentId;
        this.runId = runId;
        this.registry = registry;
        this.absorbedSteering = absorbedSteering;
    }

    @Override
    public List<AgentChatMessage.ToolResult> dispatchAll(List<AgentChatMessage.ToolCall> calls) {
        List<Planned> planned = new ArrayList<>(calls.size());
        List<ToolCallSteps.Call> toIssue = new ArrayList<>(calls.size());
        for (AgentChatMessage.ToolCall tc : calls) {
            // Minted by LlmMessageMapper in place of the provider's, so it is unique across the run
            // and stable across replays — the backend keys tool call idempotency on it.
            ToolRegistry.BackendTool bt = registry.resolve(tc.name());
            if (bt == null) {
                // The model read a deferred name in the listing: «unknown» would tell it the listing lies.
                if (registry.deferred(tc.name())) {
                    log.info("model called deferred tool {} before describing it", tc.name());
                    planned.add(new Planned(tc, null, failed(tc, deferredNotice(tc.name()))));
                    continue;
                }
                log.warn("model called unknown tool {}; {} available: {}", tc.name(), registry.names().size(), registry.names());
                planned.add(new Planned(tc, null, failed(tc,
                        "unknown tool name from model: " + tc.name() + "; available tools: " + registry.names())));
                continue;
            }
            planned.add(new Planned(tc, bt, null));
            toIssue.add(new ToolCallSteps.Call(tc.id(), bt.connectorCode(), bt.connectionId(), bt.name(),
                    tc.argumentsJson(), bt.timeoutSeconds()));
        }

        // The step runs even with nothing to issue. DBOS matches a step to its checkpoint by ordinal
        // within the workflow, so the sequence must not depend on how the names resolved: a turn whose
        // calls all came back deferred or unknown would otherwise consume no ordinal, and a replay that
        // resolved them differently would read every later step's checkpoint off by one.
        List<AgentChatMessage.ToolResult> results = new ArrayList<>(planned.size());
        // Filled by the step bodies; empty after a replay, when the contents are re-read by id.
        Map<String, String> held = new HashMap<>();
        ToolCallSteps.IssuedBatch issued = dbos.runStep(() -> step.issue(toIssue, agentId, runId), "tool_issue");
        List<ToolCallSteps.Outcome> settled = new ArrayList<>(issued.rejected());
        settled.addAll(awaitResults(issued.accepted(), held));
        ToolCallSteps.Outcomes outcomes = inCallOrder(calls, settled);

        for (Planned p : planned) {
            if (p.immediate != null) {
                results.add(p.immediate);
                continue;
            }
            results.add(result(p, outcomes.of(p.call.id()), held));
        }
        return results;
    }

    /**
     * Wait for the issued calls, one {@code recv} at a time. The timeout is the distance to the
     * nearest decision (a threshold, a ceiling, a budget), capped at
     * {@link ToolCallSteps#RECV_TIMEOUT_MS}, so an event is normally what wakes the run and a timer
     * only says «nothing was sent, look anyway». Both paths end in the same {@code observe}, which
     * is why a lost message costs at most one interval.
     */
    private List<ToolCallSteps.Outcome> awaitResults(List<ToolCallSteps.Issued> accepted, Map<String, String> held) {
        List<ToolCallSteps.Outcome> settled = new ArrayList<>(accepted.size());
        List<ToolCallSteps.Issued> waiting = accepted;
        Set<String> undetachable = new HashSet<>();
        // Sticky for the turn: the input arrived and the next turn will absorb it, so there is
        // nothing left for the model to wait for.
        boolean incoming = false;
        while (!waiting.isEmpty()) {
            // The step's inputs are rebuilt from recorded results on every pass; the lambda keeps
            // them in finals so a replay of the same pass sees the same call set.
            final List<ToolCallSteps.Issued> toObserve = waiting;
            long timeoutMs = ToolCallSteps.nextWakeMs(toObserve, new ToolCallSteps.WaitState(incoming, undetachable));
            Optional<Object> message = dbos.recv(WorkerProtocol.RUN_EVENTS_TOPIC, Duration.ofMillis(timeoutMs));
            if (message.isPresent()) {
                // A burst (a webhook storm creating run after run) must cost one observation, not one
                // per message: everything a dropped message says — a result exists, the run was
                // stopped — is re-read by the observation itself.
                RunEventInbox.Drained burst = RunEventInbox.drain(dbos);
                log.debug("run event {} while waiting for tools, {} more coalesced into this pass",
                        RunEventInbox.kind(message.get()), burst.count());
                // A `steerable` behind another message must not be lost with the rest of the burst.
                if (!incoming && needsConfirmation(message.get(), burst)) {
                    incoming = stillSteerable();
                }
            }

            final ToolCallSteps.WaitState verdict = new ToolCallSteps.WaitState(incoming, Set.copyOf(undetachable));
            ToolCallSteps.Observation observation = dbos.runStep(
                    () -> step.observe(toObserve, agentId, runId, held, verdict), "tool_observe");
            settled.addAll(observation.settled());
            List<ToolCallSteps.Issued> next = new ArrayList<>(observation.pending());

            if (!observation.toDetach().isEmpty()) {
                final List<ToolCallSteps.Issued> toDetach = observation.toDetach();
                ToolCallSteps.DetachResult detached = dbos.runStep(
                        () -> step.detach(toDetach, agentId, runId, held), "tool_detach");
                settled.addAll(detached.settled());
                undetachable.addAll(detached.undetachable());
                next.addAll(stillWaiting(toDetach, detached));
            }
            waiting = next;
        }
        return settled;
    }

    /**
     * Does anything in this wake claim there is an input waiting? A `steerable` for a message this
     * run already absorbed does not: its event outlived the reason, and the run's own ledger knows
     * it. Only what the ledger cannot explain goes on to the backend.
     */
    private boolean needsConfirmation(Object message, RunEventInbox.Drained burst) {
        if (RunEventInbox.isSteerable(message) && notAbsorbed(RunEventInbox.runId(message))) {
            return true;
        }
        return burst.steerableRuns().stream().anyMatch(this::notAbsorbed);
    }

    /** A payload that names no run cannot be checked against the ledger, so it is taken at its word. */
    private boolean notAbsorbed(String steeredRunId) {
        return steeredRunId == null || !absorbedSteering.test(steeredRunId);
    }

    /**
     * Is there still something for this run to absorb? Only the backend knows: an event outlives
     * its message — the seam may have absorbed it already, or the run it announced never arrived.
     * The claim is the cheap way to ask, because it is idempotent for this main and a claim alone
     * silences nothing: a claimed-but-unconfirmed run executes itself, so the probe costs the seam
     * nothing — it re-fetches the same messages and absorbs them for real.
     *
     * <p>Fails towards detaching: an unreachable backend leaves today's behaviour, a wait that ends
     * on the input it was told about.
     */
    private boolean stillSteerable() {
        try {
            // One attempt on purpose: a probe has a default answer, so a control-api restart must
            // not hold the wait through the client's retry ladder.
            return !client.claimSteeringOnce(agentId, runId).getMessagesList().isEmpty();
        } catch (Exception e) {
            log.warn("steering probe failed, trusting the event: {}", e.getMessage());
            return true;
        }
    }

    /**
     * The calls of the detach attempt that are still owed a result: {@code DetachTool} settled the
     * race for the rest, and a call whose detach failed goes back to waiting for its budget.
     */
    private static List<ToolCallSteps.Issued> stillWaiting(List<ToolCallSteps.Issued> toDetach,
                                                           ToolCallSteps.DetachResult detached) {
        Set<String> resolved = new HashSet<>();
        detached.settled().forEach(outcome -> resolved.add(outcome.toolCallId()));
        return toDetach.stream().filter(p -> !resolved.contains(p.toolCallId())).toList();
    }

    /** Stable shape: outcomes in call order, whatever order the steps settled them in. */
    private static ToolCallSteps.Outcomes inCallOrder(List<AgentChatMessage.ToolCall> calls,
                                                     List<ToolCallSteps.Outcome> settled) {
        List<ToolCallSteps.Outcome> ordered = new ArrayList<>(settled.size());
        for (AgentChatMessage.ToolCall call : calls) {
            settled.stream().filter(o -> o.toolCallId().equals(call.id())).findFirst().ifPresent(ordered::add);
        }
        return new ToolCallSteps.Outcomes(ordered);
    }

    private AgentChatMessage.ToolResult result(Planned p, ToolCallSteps.Outcome outcome, Map<String, String> held) {
        String id = p.call.id();
        String name = p.tool.name();
        return switch (outcome.status()) {
            case SUCCESS -> {
                String output = held.containsKey(id) ? held.get(id) : reread(id, ToolResultStatus.TOOL_RESULT_STATUS_SUCCESS);
                if (p.tool.material() == ContextMaterial.TOOLS) {
                    yield disclosure(p.call, output);
                }
                String content = output.isEmpty() ? "null" : ToolCallSteps.truncateOutput(output, step.maxOutputChars());
                yield new AgentChatMessage.ToolResult(id, p.call.name(),
                        p.tool.openWorld() ? wrapUntrusted(content) : content, false, p.tool.material());
            }
            case ERROR -> {
                String error = held.containsKey(id) ? held.get(id) : reread(id, ToolResultStatus.TOOL_RESULT_STATUS_ERROR);
                yield failed(p.call, ToolCallSteps.errorNotice(name, error));
            }
            case DETACHED -> new AgentChatMessage.ToolResult(id, p.call.name(), ToolCallSteps.detachedInterim(id), false);
            case TIMEOUT -> failed(p.call, ToolCallSteps.timeoutNotice(name, id, step.budgetSeconds(p.tool.timeoutSeconds())));
            case ABANDONED -> failed(p.call, ToolCallSteps.abandonedNotice(name, id));
            case FAILED -> failed(p.call, ToolCallSteps.errorNotice(name, outcome.error()));
        };
    }

    /**
     * A delta applied: the registry grows, the model gets the names. An output that does not parse
     * is handed over as ordinary text — the model then reads what the backend said, and the ledger
     * does not claim a disclosure that did not happen.
     */
    private AgentChatMessage.ToolResult disclosure(AgentChatMessage.ToolCall call, String output) {
        ContextDelta delta;
        try {
            delta = ContextDelta.parse(output);
        } catch (IllegalArgumentException e) {
            log.warn("tool {} is marked as a context delta but its output is not one: {}", call.name(), e.getMessage());
            String content = output.isEmpty() ? "null" : ToolCallSteps.truncateOutput(output, step.maxOutputChars());
            return new AgentChatMessage.ToolResult(call.id(), call.name(), content, false);
        }
        List<String> disclosed = registry.disclose(delta.tools());
        return new AgentChatMessage.ToolResult(call.id(), call.name(),
                ContextDelta.modelFacing(disclosed, delta.unknown()), false, ContextMaterial.TOOLS);
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

    static String deferredNotice(String name) {
        return "tool " + name + " is listed but not described yet: call load_tools with its name"
                + " (batch every tool you will need) and then call it with the arguments its schema defines";
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
