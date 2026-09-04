package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.workflow.Queue;
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
import ru.agimate.agentworker.grpc.AgentWorkerClient;
import ru.agimate.agentworker.workers.LlmCallWorkflow;
import ru.agimate.agentworker.workers.ToolCallWorkflow;

import java.util.ArrayList;
import java.util.List;

/**
 * The invariant run body: prepare the context, drive {@link AgiMateAgent}, record the answer,
 * report failures. History arrives pre-assembled in the {@link PreparedContext}; every loop event
 * becomes a backend record through the run's {@link BackendRunRecorder}, and the dialogue events among
 * them go out as durable steps of its {@link MessageLog}. LLM/tool calls are dispatched as child
 * workflows by {@link LlmCallDispatcher}/{@link ToolCallDispatcher}.
 */
@Slf4j
public class AgentRunCore {

    private final DBOS dbos;
    private final AgentWorkerClient client;
    private final ContextMaterialsFetcher fetcher;
    private final LlmCallWorkflow llm;
    private final ToolCallWorkflow tool;
    private final Queue llmQueue;
    private final Queue toolQueue;
    private final ResponseTemplates templates;
    private final int maxTurns;

    public AgentRunCore(DBOS dbos, AgentWorkerClient client, LlmCallWorkflow llm, ToolCallWorkflow tool,
                        Queue llmQueue, Queue toolQueue, ResponseTemplates templates, int maxTurns) {
        this.dbos = dbos;
        this.client = client;
        this.fetcher = new ContextMaterialsFetcher(client);
        this.llm = llm;
        this.tool = tool;
        this.llmQueue = llmQueue;
        this.toolQueue = toolQueue;
        this.templates = templates;
        this.maxTurns = maxTurns;
    }

    /** The run's dialogue-event writer; created here so the workflow shares one seq counter. */
    public MessageLog messageLog(String agentId, String runId) {
        return new MessageLog(dbos, client, agentId, runId);
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
    public PreparedContext prepareContext(String agentId, String runId) {
        return ContextBuilder.build(fetcher.fetch(agentId, runId));
    }

    /**
     * Run the agent loop body: history and the user prompt come from {@code prepared}, per-turn
     * progress and the final answer are recorded via {@code messages} (the backend persists and
     * delivers). Ephemeral blocks (memory notes) are prepended to the model turn and stay out of the
     * dialogue history that feeds later runs — the turn ledger and the prompt snapshot keep them.
     */
    public String run(String agentId, String runId, PreparedContext prepared, MessageLog messages,
                      String context) {
        ToolRegistry registry = prepared.registry();
        BackendRunRecorder recorder = new BackendRunRecorder(client, messages, registry, agentId, runId);

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
                new LlmCallDispatcher(dbos, llm, llmQueue, agentId),
                new ToolCallDispatcher(dbos, tool, toolQueue, agentId, runId, registry),
                registry.toolDefs(), maxTurns, templates.wrapUp(), recorder);
        recorder.recordInbound(initialRequest);
        String answer;
        try {
            answer = agent.run(conv);
        } catch (RunCancelled e) {
            // A stop ends the run with an ANSWER, not an ERROR: that is what marks the run completed, so
            // the next run sees it was interrupted instead of suffering unexplained amnesia.
            answer = cancellationNotice(e);
            log.info("run cancelled by the user after {} executed tool(s)", e.executedTools().size());
        } catch (MaxTurnsExceeded | EmptyAnswerExhausted | LlmResponseIncomplete | LlmCallError e) {
            throw abortFor(e, context);
        }
        messages.answer(answer);
        return answer;
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
    public void reportFailure(MessageLog messages, AgentRunAborted exc) {
        // Best-effort: the channel may be unreachable, but the system report below must go out regardless.
        if (exc.userNotice() != null && !exc.userNotice().isEmpty()) {
            try {
                messages.error(exc.userNotice());
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
    public void reportInfraFailure(MessageLog messages, String systemDetail) {
        try {
            messages.error(templates.infraError());
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
