package ru.agimate.agentworker.workers.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dbos.transact.DBOS;
import dev.dbos.transact.workflow.Queue;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.WorkerMessageType;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.LlmMeta;
import ru.agimate.agentworker.agent.model.LlmUsage;
import ru.agimate.agentworker.agent.error.AgentRunAborted;
import ru.agimate.agentworker.agent.AgentRunner;
import ru.agimate.agentworker.agent.MessageCodec;
import ru.agimate.agentworker.agent.ResponseTemplates;
import ru.agimate.agentworker.agent.SimpleAgent;
import ru.agimate.agentworker.agent.ToolRegistry;
import ru.agimate.agentworker.agent.context.ContextBuilder;
import ru.agimate.agentworker.grpc.AgentWorkerClient;
import ru.agimate.agentworker.workers.LlmCallWorkflow;
import ru.agimate.agentworker.workers.ToolCallWorkflow;

import java.util.List;

/**
 * Shared agent-run core: one implementation of the invariant run body (prepare context, drive
 * {@link AgentRunner}, record output, report failures). History arrives pre-assembled in the
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
     * prompt + tool registry in one durable step. The assembly policy lives server-side
     * (ContextSpec); the worker only renders the blocks.
     */
    public PreparedContext prepareContext(String agentId, String runId) {
        return dbos.runStep(() -> ContextBuilder.build(fetcher.fetch(agentId, runId)),
                "prepare_context");
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
        SimpleAgent.RunObserver observer = new SimpleAgent.RunObserver() {
            @Override
            public void onStart(List<AgentChatMessage> startMessages) {
                reportPrompt(agentId, runId, startMessages);
            }

            @Override
            public void onMessages(List<AgentChatMessage> newMsgs, LlmMeta meta) {
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
        };

        AgentChatMessage initialRequest = AgentChatMessage.user(prepared.userPrompt(), prepared.inboundParts());
        AgentChatMessage modelRequest = withEphemeralPrefix(prepared.ephemeralUserPrefix(), initialRequest);

        LlmCallDispatcher llmDispatcher = new LlmCallDispatcher(dbos, llm, llmQueue, agentId);
        ToolCallDispatcher toolDispatcher = new ToolCallDispatcher(dbos, tool, toolQueue, agentId,
                runId, registry);

        AgentRunner runner = new AgentRunner(llmDispatcher, toolDispatcher, registry.toolDefs(), maxTurns,
                context, observer, templates);
        // Turn 0 is the inbound one — as the model got it, ephemeral prefix included, the same text the
        // prompt snapshot keeps. Without it the transcript of a direct run opens with the answer and the
        // question exists nowhere but inside that snapshot's JSON (a direct run has no channel history).
        // Not routed through the observer: the channel already showed the user their own message.
        turns.record(initialRequest, null);
        String answer = runner.run(prepared.systemPrompt(), prepared.history(), modelRequest);
        messages.answer(answer);
        return answer;
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
     * message, if any — reference data goes ahead of the request the model must act on. The ephemeral
     * text never rides into the dialogue history that feeds later runs; the observability records —
     * the prompt snapshot and the turn ledger — do keep it, because both answer «what did the model
     * see», and the notes are part of that.
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
