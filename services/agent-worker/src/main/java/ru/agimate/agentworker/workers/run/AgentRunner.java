package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.workflow.Workflow;
import dev.dbos.transact.workflow.WorkflowClassName;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import ru.agimate.agentworker.WorkerMessageType;
import ru.agimate.agentworker.agent.*;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.error.AgentRunAborted;
import ru.agimate.agentworker.agent.error.EmptyAnswerExhausted;
import ru.agimate.agentworker.agent.error.LlmCallError;
import ru.agimate.agentworker.agent.error.LlmResponseIncomplete;
import ru.agimate.agentworker.agent.error.MaxTurnsExceeded;
import ru.agimate.agentworker.agent.error.RunCancelled;
import ru.agimate.agentworker.agent.context.ContextBuilder;
import ru.agimate.agentworker.agent.context.PreparedContext;
import ru.agimate.agentworker.dto.AgentMessage;
import ru.agimate.agentworker.grpc.AgentWorkerClient;
import ru.agimate.agentworker.workers.AgentRunWorkflow;
import ru.agimate.agentworker.workers.Queues;

import java.util.ArrayList;
import java.util.List;

/**
 * The worker's only workflow, and the whole run lifecycle behind it. Its queue partition is the
 * run's session with concurrency 1 — single-writer is the queue's contract, not a registration
 * handshake; run status is a backend-side projection of this run's {@code SaveMessage} stream.
 *
 * <p>The lifecycle is uniform for dialogue and trigger runs (which is which is server-side policy,
 * ContextSpec): ack, leave early if cancelled or steered while queued, render the context, drive
 * {@link AgiMateAgent}, record the answer, report failures. Loop events become backend records
 * through {@link BackendRunRecorder}, the dialogue ones as durable steps of
 * {@link ChannelMessageLog}; the checkpoints of {@link LlmCallDispatcher} and
 * {@link ToolCallDispatcher} hold identifiers, not the dialogue.
 */
@Slf4j
@WorkflowClassName(Queues.RUN_CLASS)
public class AgentRunner implements AgentRunWorkflow {

    private final DBOS dbos;
    private final AgentWorkerClient client;
    private final ContextMaterialsFetcher fetcher;
    private final ContextBuilder contextBuilder;
    private final LlmCall llmCall;
    private final ToolCallStep toolStep;
    private final ResponseTemplates templates;
    private final int maxTurns;

    public AgentRunner(DBOS dbos, AgentWorkerClient client, LlmCall llmCall, ToolCallStep toolStep,
                        ResponseTemplates templates, int maxTurns) {
        this.dbos = dbos;
        this.client = client;
        this.fetcher = new ContextMaterialsFetcher(client);
        this.contextBuilder = new ContextBuilder(templates);
        this.llmCall = llmCall;
        this.toolStep = toolStep;
        this.templates = templates;
        this.maxTurns = maxTurns;
    }

    @Override
    @Workflow(name = Queues.RUN_WORKFLOW)
    public void runAgent(AgentMessage message) {
        // Tag every line of the run with a short run id — model and tool steps run on this thread too.
        try (MDC.MDCCloseable __ = MDC.putCloseable("run", shortRun(message.runId()))) {
            run(message);
        }
    }

    /**
     * Last 8 hex of the run's UUID — enough to correlate a run's lines in the console. Taken from
     * the tail (random {@code rand_b}), not the head: run ids are UUIDv7, whose leading hex encode
     * the millisecond clock and stay identical for runs within the same ~65s window.
     */
    private static String shortRun(String runId) {
        return runId != null && runId.length() >= 8 ? runId.substring(runId.length() - 8) : runId;
    }

    /**
     * Run one agent run to its end. A soft abort ({@link AgentRunAborted}) is reported to the user
     * and the backend and ends the run quietly; anything else is reported best-effort and rethrown,
     * so the workflow goes to ERROR — terminally, since recovery only replays PENDING.
     */
    private void run(AgentMessage message) {
        String agentId = message.agentId();
        String runId = message.runId();
        // Created once per run: the workflow's dialogue events share one seq counter.
        ChannelMessageLog channelLog = new ChannelMessageLog(dbos, client, agentId, runId);
        try {
            log.info("run started: agent={} run={}", agentId, runId);

            // The «agent received it» ack — seq 0, before the context is fetched: receipt must not depend
            // on the fetch succeeding. On the backend this same step moves the run's status to RUNNING.
            channelLog.inbound();

            // Cancelled while queued: nothing happened, so there is nothing to report. Leaving before the
            // fetch also keeps the channel from collecting one «stopped» line per run left in the partition.
            if (channelLog.isCancelRequested()) {
                log.info("run cancelled before it started");
                return;
            }

            // Absorbed by an earlier run of the session (steering) — leave as quietly as a queued
            // cancellation. The backend answers steered=true only once the main run finished
            // DONE/CANCELLED, so a failed main never silences the message.
            if (channelLog.isSteered()) {
                log.info("run steered into an earlier run of the session");
                return;
            }

            channelLog.answer(driveAgent(agentId, runId, channelLog));
            log.info("run finished");
        } catch (AgentRunAborted e) {
            log.warn(e.systemDetail());
            reportFailure(channelLog, e);
        } catch (Exception e) {
            // An infra error (a step's retries exhausted and the like): a best-effort notice so the user is
            // not left in silence, then a rethrow — the workflow's ERROR status is preserved.
            reportInfraFailure(channelLog,
                    "agent run infra failure: agent_id=" + agentId + " run=" + runId + ": " + e);
            throw e;
        }
    }

    /**
     * Fetch the backend-assembled run context ({@code GetRunContext}) and render it into the prompt
     * + tool registry; the assembly policy lives server-side (ContextSpec).
     *
     * <p>Deliberately not a durable step: that checkpoint held the whole assembled conversation,
     * making the DBOS system database a second store of the dialogue. A crash replay therefore
     * re-fetches instead of restoring — it gets today's context, and fails outright if the agent was
     * disabled meanwhile. Both are rare, and the model calls already made keep their checkpoints.
     */
    private PreparedContext prepareContext(String agentId, String runId) {
        return contextBuilder.build(fetcher.fetch(agentId, runId));
    }

    /**
     * The body of a run that is actually going to happen: render the context, assemble the per-run
     * machinery (turn ledger, recorder, dispatchers) and drive the agent to its answer. Per-turn
     * progress is recorded via {@code channelLog}; the final answer is returned for the caller.
     */
    private String driveAgent(String agentId, String runId, ChannelMessageLog channelLog) {
        PreparedContext prepared = prepareContext(agentId, runId);
        ToolRegistry toolRegistry = prepared.toolRegistry();
        // One ledger counter for its three writers: the recorder, the steering absorber and the llm_call step.
        TurnLog turnLog = new TurnLog(client, agentId, runId);
        BackendRunRecorder recorder = new BackendRunRecorder(client, channelLog, turnLog, toolRegistry, templates, agentId, runId);

        AgentChatMessage initialRequest = AgentChatMessage.user(prepared.userPrompt(), prepared.inboundParts());
        AgentChatMessage modelRequest = withEphemeralPrefix(prepared.ephemeralUserPrefix(), initialRequest);

        // The system prompt is rebuilt every run — otherwise a history-trim could drop it, and a
        // spec change would only take effect on the next run.
        List<AgentChatMessage> conv = new ArrayList<>();
        conv.add(AgentChatMessage.system(prepared.systemPrompt()));
        conv.addAll(prepared.history());
        conv.add(modelRequest);

        AgiMateAgent agent = new AgiMateAgent(
                new LlmCallDispatcher(dbos, llmCall, turnLog, client, agentId, runId),
                new ToolCallDispatcher(dbos, toolStep, client, agentId, runId, toolRegistry),
                toolRegistry::toolDefs, maxTurns, templates.wrapUp(), recorder);
        // Turn 0: the inbound message without the ephemeral prefix. Not a loop event, so it is
        // recorded here; without it a direct run's transcript would open with the answer.
        turnLog.record(initialRequest, null);
        try {
            return agent.run(conv);
        } catch (RunCancelled e) {
            // A stop ends the run with an ANSWER, not an ERROR: that is what marks the run completed, so
            // the next run sees it was interrupted instead of suffering unexplained amnesia.
            log.info("run cancelled by the user after {} executed tool(s)", e.executedTools().size());
            return cancellationNotice(e);
        } catch (MaxTurnsExceeded | EmptyAnswerExhausted | LlmResponseIncomplete | LlmCallError e) {
            throw abortFor(e, "for agent_id=" + agentId + " run=" + runId);
        }
    }

    /** The user notice for each terminal loop failure; the system detail keeps the loop's own message. */
    private AgentRunAborted abortFor(RuntimeException e, String context) {
        return switch (e) {
            case MaxTurnsExceeded ex -> new AgentRunAborted(templates.maxTurns(),
                    "agent loop hit max_turns " + context + ": " + ex.getMessage());
            case EmptyAnswerExhausted ex -> new AgentRunAborted(templates.emptyAnswer(),
                    "model returned an empty answer " + context + ": " + ex.getMessage());
            case LlmResponseIncomplete ex -> new AgentRunAborted(
                    switch (ex.reason()) {
                        // BROKEN shares the notice with LENGTH on purpose: to the user both are one
                        // thing — the answer stops mid-sentence — and only the log needs to know
                        // whether the model ran out of tokens or the stream died under it.
                        case LENGTH, BROKEN -> templates.truncated();
                        case CONTENT_FILTER -> templates.filtered();
                    },
                    "llm response incomplete (" + ex.reason() + ") " + context + ": " + ex.getMessage());
            case LlmCallError ex -> llmCallAbort(ex, context);
            default -> throw e;
        };
    }

    private AgentRunAborted llmCallAbort(LlmCallError e, String context) {
        // The call already produced a ready user notice (a quota text, «no model configured») — verbatim.
        if (e.userFacing()) {
            return new AgentRunAborted(e.getMessage(), "LLM call aborted " + context + ": " + e.getMessage());
        }
        Integer status = e.statusCode();
        if (status != null && (status == 401 || status == 403)) {
            return new AgentRunAborted(templates.authError(),
                    "LLM auth error (HTTP " + status + ") " + context + ": " + e.getMessage());
        }
        String prefix = status != null ? "LLM HTTP error (HTTP " + status + ")" : "LLM API error";
        return new AgentRunAborted(templates.modelError(), prefix + " " + context + ": " + e.getMessage());
    }

    /** The receipt: composed here because only the loop knows what ran, and without a model call. */
    private String cancellationNotice(RunCancelled cancelled) {
        if (cancelled.executedTools().isEmpty()) {
            return templates.cancelled();
        }
        return templates.cancelled() + " " + templates.cancelledDidRun()
                + " " + String.join(", ", cancelled.executedTools());
    }

    /**
     * The ephemeral block (memory notes etc.) prepended to the user's message — reference data goes
     * ahead of the request the model must act on. It reaches the model and the prompt snapshot
     * ({@code agent_runs.prompt}) but not the turn ledger: later runs read the ledger back as
     * history, and today's notes must not settle into tomorrow's context.
     */
    private static AgentChatMessage withEphemeralPrefix(String prefix, AgentChatMessage initialRequest) {
        if (prefix == null || prefix.isBlank()) {
            return initialRequest;
        }
        String base = initialRequest.text() != null ? initialRequest.text() : "";
        // Attachments move onto the prefixed turn — otherwise «vision» would be lost during memory notes.
        return AgentChatMessage.user(prefix + "\n\n" + base, initialRequest.parts());
    }

    /**
     * Report a terminal soft-abort to both sides: the user notice as an ERROR dialogue event when
     * present, the system detail always to the backend.
     */
    private void reportFailure(ChannelMessageLog channelLog, AgentRunAborted exc) {
        // Best-effort: the channel may be unreachable, but the system report below must go out regardless.
        if (exc.userNotice() != null && !exc.userNotice().isEmpty()) {
            try {
                channelLog.error(exc.userNotice());
            } catch (Exception e) {
                log.warn("failed to send abort notice to the channel: {}", e.getMessage());
            }
        }
        sendSystemReport(WorkerMessageType.WORKER_MESSAGE_TYPE_MESSAGE, exc.systemDetail());
    }

    /**
     * Reported before the workflow goes to ERROR. The likely cause is control-api being unreachable,
     * so either send may fail too — both are swallowed so the original exception, rethrown by the
     * caller, stays the recorded failure.
     */
    private void reportInfraFailure(ChannelMessageLog channelLog, String systemDetail) {
        try {
            channelLog.error(templates.infraError());
        } catch (Exception e) {
            log.warn("failed to send infra-error notice to the channel: {}", e.getMessage());
        }
        sendSystemReport(WorkerMessageType.WORKER_MESSAGE_TYPE_ERROR, systemDetail);
    }

    /**
     * The system detail of a run's outcome. A durable step, so a crash replay does not duplicate it,
     * and best-effort, so a failed report does not mask the outcome it reports. {@code type} sets the
     * backend-side level — MESSAGE (an expected abort) or ERROR (infra). The step returns a boolean:
     * a proto response must never go into a checkpoint.
     */
    private void sendSystemReport(WorkerMessageType type, String detail) {
        try {
            dbos.runStep(() -> {
                client.sendMessage(type, detail);
                return true;
            }, "report_failure");
        } catch (Exception e) {
            log.warn("failed to report the run outcome to the backend: {}", e.getMessage());
        }
    }
}
