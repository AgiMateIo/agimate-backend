package ru.agimate.agentworker.workers.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dbos.transact.DBOS;
import dev.dbos.transact.workflow.Queue;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.WorkerMessageType;
import ru.agimate.agentworker.agent.*;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.LlmMeta;
import ru.agimate.agentworker.agent.model.LlmUsage;
import ru.agimate.agentworker.agent.error.AgentRunAborted;
import ru.agimate.agentworker.agent.error.EmptyAnswerExhausted;
import ru.agimate.agentworker.agent.error.LlmCallError;
import ru.agimate.agentworker.agent.error.LlmResponseIncomplete;
import ru.agimate.agentworker.agent.error.MaxTurnsExceeded;
import ru.agimate.agentworker.agent.error.RunCancelled;
import ru.agimate.agentworker.agent.context.ContextBuilder;
import ru.agimate.agentworker.grpc.AgentWorkerClient;
import ru.agimate.agentworker.workers.LlmCallWorkflow;
import ru.agimate.agentworker.workers.ToolCallWorkflow;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared agent-run core: one implementation of the invariant run body (prepare context, drive
 * {@link AgiMateAgent}, record output, report failures). History arrives pre-assembled in the
 * {@link PreparedContext} (backend-side window/filter); all dialogue events go out through the
 * run's {@link MessageLog} — persistence and channel delivery are its backend-side projections.
 * LLM/tool dispatch in {@link LlmCallDispatcher}/{@link ToolCallDispatcher}. Durable checkpoints
 * are {@code dbos.runStep};
 * the LLM/tool calls are enqueued as child workflows.
 */
@Slf4j
public class AgentRunCore {

    private static final ObjectMapper PROMPT_MAPPER = new ObjectMapper();

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

        // The canonical turn journal (agent_run_turns): one record per message, uncapped and for every run.
        // An ordinary (non-durable) call — a projection of already-durable data, deduplicated by
        // (run_id, turn_index) on the backend. Written alongside the channel projection, not instead of it.
        TurnLog turns = new TurnLog(client, agentId, runId);

        // A single observer of the run's events — the run wiring is the only writer of backend-side records:
        //  • onStart — the snapshot of the starting prompt (what went into the first LLM call) into
        //    agent_runs.prompt, once before the loop; later turns are written by TurnLog. First-write-wins on the backend.
        //  • onMessages — every turn as a separate call (v2.1a): the assistant with its calls before the dispatch →
        //    TOOL_CALL (plus the preamble/thinking), then the tool results → a separate TOOL_RESULT entry;
        //    the backend assembles the history of later runs out of that pair into native tool_use/tool_result.
        //  • onUsage — token usage accounting, best-effort, idempotent by call_id (a replay deduplicates).
        //  • pollSteering — the seam absorbing the session's queued messages; the claim, the ledger
        //    record, the duplicate guard and the confirmation live in SteeringAbsorber, so the loop
        //    stays pure.
        SteeringAbsorber steering = new SteeringAbsorber(client, turns, agentId, runId);

        RunObserver observer = new RunObserver() {
            @Override
            public void onStart(List<AgentChatMessage> startMessages) {
                reportPrompt(agentId, runId, startMessages);
            }

            @Override
            public void onMessages(List<AgentChatMessage> newMsgs, LlmMeta meta) {
                if (newMsgs.stream().anyMatch(m -> m.role() == AgentChatMessage.Role.ASSISTANT)) {
                    steering.confirmOnAssistantTurn();
                }
                for (AgentChatMessage m : newMsgs) {
                    turns.record(m, meta);
                    if (m.role() == AgentChatMessage.Role.ASSISTANT) {
                        for (MessageCodec.ProgressLine line
                                : MessageCodec.progressLines(m, registry.displayNames(m))) {
                            messages.progress(line);
                        }
                    } else if (m.role() == AgentChatMessage.Role.TOOL) {
                        messages.progress(MessageCodec.toolResultLine(m));
                    }
                }
            }

            @Override
            public void onUsage(LlmUsage usage) {
                reportUsage(agentId, runId, usage);
            }

            @Override
            public boolean cancelRequested() {
                return messages.isCancelRequested();
            }

            @Override
            public List<AgentChatMessage> pollSteering() {
                return steering.poll();
            }
        };

        AgentChatMessage initialRequest = AgentChatMessage.user(prepared.userPrompt(), prepared.inboundParts());
        AgentChatMessage modelRequest = withEphemeralPrefix(prepared.ephemeralUserPrefix(), initialRequest);

        LlmCallDispatcher llmDispatcher = new LlmCallDispatcher(dbos, llm, llmQueue, agentId);
        ToolCallDispatcher toolDispatcher = new ToolCallDispatcher(dbos, tool, toolQueue, agentId,
                runId, registry);

        // The model-side list: system + backend-assembled history + the trigger (with its ephemeral
        // prefix, if any). The system prompt is rebuilt every run — otherwise a history-trim could
        // drop it, and a spec change would only take effect on the next run. The trigger already
        // carries the ephemeral block from withEphemeralPrefix, so the model sees it once.
        List<AgentChatMessage> conv = new ArrayList<>();
        conv.add(AgentChatMessage.system(prepared.systemPrompt()));
        conv.addAll(prepared.history());
        conv.add(modelRequest);

        AgiMateAgent agent = new AgiMateAgent(llmDispatcher, toolDispatcher, registry.toolDefs(), maxTurns,
                templates.wrapUp(), observer);
        // Turn 0 is the inbound one, without the ephemeral prefix — the persistent part of the turn.
        // Without it the transcript of a direct run opens with the answer and the question exists
        // nowhere but inside the prompt snapshot's JSON (a direct run has no channel history).
        // Not routed through the observer: the channel already showed the user their own message.
        turns.record(initialRequest, null);
        String answer;
        try {
            answer = agent.run(conv);
        } catch (MaxTurnsExceeded e) {
            throw new AgentRunAborted(templates.maxTurns(),
                    "agent loop hit max_turns " + context + ": " + e.getMessage());
        } catch (EmptyAnswerExhausted e) {
            throw new AgentRunAborted(templates.emptyAnswer(),
                    "model returned an empty answer " + context + ": " + e.getMessage());
        } catch (LlmResponseIncomplete e) {
            String userNotice = switch (e.reason()) {
                case LENGTH -> templates.truncated();
                case CONTENT_FILTER -> templates.filtered();
            };
            throw new AgentRunAborted(userNotice,
                    "llm response incomplete (" + e.reason() + ") " + context + ": " + e.getMessage());
        } catch (LlmCallError e) {
            // The call already produced a ready user notice (a quota text, «no model configured») — verbatim.
            if (e.userFacing()) {
                throw new AgentRunAborted(e.getMessage(),
                        "LLM call aborted " + context + ": " + e.getMessage());
            }
            Integer status = e.statusCode();
            String userNotice;
            String prefix;
            if (status != null && (status == 401 || status == 403)) {
                userNotice = templates.authError();
                prefix = "LLM auth error (HTTP " + status + ")";
            } else if (status != null) {
                userNotice = templates.modelError();
                prefix = "LLM HTTP error (HTTP " + status + ")";
            } else {
                userNotice = templates.modelError();
                prefix = "LLM API error";
            }
            throw new AgentRunAborted(userNotice, prefix + " " + context + ": " + e.getMessage());
        } catch (RunCancelled e) {
            // A stop ends the run with an ANSWER, not an ERROR: that is what marks the run completed, so
            // the next run sees it was interrupted instead of suffering unexplained amnesia.
            answer = cancellationNotice(e);
            log.info("run cancelled by the user after {} executed tool(s)", e.executedTools().size());
        }
        messages.answer(answer);
        return answer;
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
     * Reports the call's tokens to the backend ({@code reportLlmUsage}). Skipped without a call_id
     * (the idempotency key). Best-effort: a failure is logged and does not fail the run — accounting
     * is observability.
     */
    private void reportUsage(String agentId, String runId, LlmUsage usage) {
        if (usage.callId() == null || usage.callId().isBlank()) {
            log.warn("no call id — skipping usage report");
            return;
        }
        try {
            client.reportLlmUsage(usage.callId(), agentId, runId, usage.providerId(), usage.model(),
                    usage.promptTokens(), usage.completionTokens(),
                    usage.cacheReadTokens(), usage.cacheWriteTokens());
        } catch (Exception e) {
            log.warn("LLM usage report failed (best-effort): {}", e.getMessage());
        }
    }

    /**
     * Snapshot of the run's starting prompt ({@code savePrompt} → {@code agent_runs.prompt}): the
     * message list exactly as it went into the first LLM call (system + history + trigger with its
     * ephemeral prefix). Serialised as-is (attachments as references, not bytes). Best-effort: a
     * failure is logged and does not fail the run — the snapshot is observability. The backend writes
     * first-write-wins, so a replay does not overwrite it.
     */
    private void reportPrompt(String agentId, String runId, List<AgentChatMessage> messages) {
        try {
            client.savePrompt(agentId, runId, PROMPT_MAPPER.writeValueAsString(messages));
        } catch (Exception e) {
            log.warn("prompt snapshot report failed (best-effort): {}", e.getMessage());
        }
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
