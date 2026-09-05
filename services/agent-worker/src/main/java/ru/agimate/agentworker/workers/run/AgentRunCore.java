package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.DBOS;
import lombok.extern.slf4j.Slf4j;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The run's lifecycle, uniform for dialogue and trigger runs (which is which is server-side
 * policy, ContextSpec): acknowledge receipt, leave early if the run was cancelled or steered
 * while queued, fetch and render the context, drive {@link AgiMateAgent}, record the answer,
 * report failures. History arrives pre-assembled in the {@link PreparedContext}; every loop event
 * becomes a backend record through the run's {@link BackendRunRecorder}, and the dialogue events among
 * them go out as durable steps of its {@link ChannelMessageLog}. Model requests and tool calls are
 * durable steps of the run workflow ({@link LlmCallDispatcher}/{@link ToolCallDispatcher}) whose
 * checkpoints hold identifiers, not the dialogue.
 */
@Slf4j
public class AgentRunCore {

    private final DBOS dbos;
    private final AgentWorkerClient client;
    private final ContextMaterialsFetcher fetcher;
    private final ContextBuilder contextBuilder;
    private final LlmCall llmCall;
    private final ToolCallStep toolStep;
    private final ResponseTemplates templates;
    private final int maxTurns;

    public AgentRunCore(DBOS dbos, AgentWorkerClient client, LlmCall llmCall, ToolCallStep toolStep,
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

    /**
     * Run one agent run to its end. A soft abort ({@link AgentRunAborted}) is reported to the user
     * and the backend and ends the run quietly; anything else is reported best-effort and rethrown,
     * so the workflow goes to ERROR — terminally, since recovery only replays PENDING.
     */
    public void run(AgentMessage message) {
        String agentId = message.agentId();
        String runId = message.runId();
        // Created once per run: the workflow's dialogue events share one seq counter.
        ChannelMessageLog channelLog = new ChannelMessageLog(dbos, client, agentId, runId);
        try {
            log.info("run started: agent={} run={}", agentId, runId);

            // The «agent received it» ack — the first durable dialogue step (seq 0), before the context is
            // fetched: recording receipt does not depend on the fetch succeeding. On the backend the same
            // step moves the run's status to RUNNING (the projection of the SaveMessage stream).
            channelLog.inbound();

            // Cancelled while it was still queued: nothing has happened yet, so there is nothing to report.
            // Leaving here also saves a full GetRunContext, and — more visibly — keeps the channel from
            // collecting one «stopped» line per run standing in the partition.
            if (channelLog.isCancelRequested()) {
                log.info("run cancelled before it started");
                return;
            }

            // Absorbed by an earlier run of the session (steering): the message was answered before this
            // workflow ever reached the front of the partition — leave as quietly as a queued cancellation.
            // The backend answers steered=true only when the absorption was confirmed AND the main finished
            // DONE/CANCELLED, so a failed main never silences the message.
            if (channelLog.isSteered()) {
                log.info("run steered into an earlier run of the session");
                return;
            }

            PreparedContext prepared = prepareContext(agentId, runId);
            channelLog.answer(runLoop(agentId, runId, prepared, channelLog));
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
     * Fetch the backend-assembled run context ({@code GetRunContext}) and render it into the
     * prompt + tool registry. The assembly policy lives server-side (ContextSpec); the worker only
     * renders the blocks.
     *
     * <p>Deliberately not a durable step: that checkpoint held the whole assembled conversation —
     * system prompt, the user's message, memory notes and the entire history window — which made
     * the DBOS system database a second store of the dialogue. The price is paid by a crash replay:
     * it re-fetches instead of restoring, so it gets today's context rather than the one the run
     * started with, and fails outright if the agent was disabled meanwhile. Both are rare (a replay
     * needs the process to die mid-run) and the model calls already made keep their own checkpoints.
     */
    private PreparedContext prepareContext(String agentId, String runId) {
        return contextBuilder.build(fetcher.fetch(agentId, runId));
    }

    /**
     * The agent loop: history and the user prompt come from {@code prepared}, per-turn progress is
     * recorded via {@code channelLog} (the backend persists and delivers); the final answer is
     * returned for the caller to record. Ephemeral blocks (memory notes) are prepended to the model
     * turn and stay out of the dialogue history that feeds later runs — the turn ledger and the
     * prompt snapshot keep them.
     */
    private String runLoop(String agentId, String runId, PreparedContext prepared, ChannelMessageLog channelLog) {
        ToolRegistry toolRegistry = prepared.toolRegistry();
        // One ledger counter for its three writers: the recorder, the steering absorber and the llm_call step.
        TurnLog turnLog = new TurnLog(client, agentId, runId);
        BackendRunRecorder recorder = new BackendRunRecorder(client, channelLog, turnLog, toolRegistry, templates, agentId, runId);

        AgentChatMessage initialRequest = AgentChatMessage.user(prepared.userPrompt(), prepared.inboundParts());
        AgentChatMessage modelRequest = withEphemeralPrefix(prepared.ephemeralUserPrefix(), initialRequest);

        // The model-side list: system + backend-assembled history + the trigger (with its ephemeral
        // prefix, if any). The system prompt is rebuilt every run — otherwise a history-trim could
        // drop it, and a spec change would only take effect on the next run. The trigger already
        // carries the ephemeral block from withEphemeralPrefix, so the model sees it once.
        List<AgentChatMessage> conv = new ArrayList<>();
        conv.add(AgentChatMessage.system(prepared.systemPrompt()));
        conv.addAll(prepared.history());
        conv.add(modelRequest);

        AgiMateAgent agent = new AgiMateAgent(
                new LlmCallDispatcher(dbos, llmCall, turnLog, client, agentId, runId),
                new ToolCallDispatcher(dbos, toolStep, client, agentId, runId, toolRegistry),
                toolRegistry.toolDefs(), maxTurns, templates.wrapUp(), recorder);
        // Turn 0: the inbound message without the ephemeral prefix — the persistent part of the turn.
        // Not a loop event (the channel already showed the user their own message), so it is
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
                        case LENGTH -> templates.truncated();
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
     * Model-facing user turn: the ephemeral block (memory notes etc.) prepended before the user's
     * message, if any — reference data goes ahead of the request the model must act on. The prefix
     * reaches the model and the prompt snapshot ({@code agent_runs.prompt}), which answers «what did
     * the model see». The turn ledger keeps the message without it: later runs read the ledger back as
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
     * Report a terminal soft-abort to both sides. The user notice goes out as an ERROR dialogue
     * event when present; the system detail always reaches the backend via
     * {@code WorkerControl.SendMessage}.
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
     * Best-effort report of an unexpected infra failure before the workflow goes to ERROR. The
     * likely cause is control-api being unreachable, so either send may fail as well — both are
     * swallowed so the original exception (rethrown by the caller) stays the recorded failure.
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
     * The system detail of a run's outcome, sent to the backend as a durable step (a crash replay
     * does not duplicate the report) and best-effort: a failure of the report itself must not mask
     * the run's outcome. {@code type} sets the level on the backend — MESSAGE (an expected abort →
     * INFO) or ERROR (an infra failure). The step's result is a boolean: a proto response must never
     * go into a checkpoint.
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
