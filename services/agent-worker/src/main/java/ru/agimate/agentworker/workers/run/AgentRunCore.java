package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.workflow.Queue;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.GetHistoryResponse;
import ru.agimate.agentworker.HistoryMessage;
import ru.agimate.agentworker.MessageKind;
import ru.agimate.agentworker.WorkerMessageType;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.error.AgentRunAborted;
import ru.agimate.agentworker.agent.AgentRunner;
import ru.agimate.agentworker.agent.MessageCodec;
import ru.agimate.agentworker.agent.SimpleAgent;
import ru.agimate.agentworker.agent.ToolRegistry;
import ru.agimate.agentworker.agent.context.ContextBuilder;
import ru.agimate.agentworker.agent.context.ContextProfile;
import ru.agimate.agentworker.agent.context.RequestBuilder;
import ru.agimate.agentworker.dto.AgentMessage;
import ru.agimate.agentworker.dto.Trigger;
import ru.agimate.agentworker.grpc.AgentWorkerClient;
import ru.agimate.agentworker.workers.ControlSignal;
import ru.agimate.agentworker.workers.LlmCallWorkflow;
import ru.agimate.agentworker.workers.Queues;
import ru.agimate.agentworker.workers.ToolCallWorkflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shared agent-run core used by the orchestrator workflow — one implementation of the invariant run
 * body (prepare context, restore session, drive {@link AgentRunner}, route output, report failures),
 * parameterized by the per-entry policies that differ (dialogue vs trigger scope; session vs
 * stateless). Durable checkpoints are {@code dbos.runStep(...)}; the LLM/tool calls are enqueued as
 * child workflows by {@link AgentDispatcher}.
 */
@Slf4j
public class AgentRunCore {

    private static final int MAX_AGENT_TURNS = 30;
    private static final int HISTORY_WINDOW_MESSAGES = 50;

    private final DBOS dbos;
    private final AgentWorkerClient client;
    private final ContextMaterialsFetcher fetcher;
    private final LlmCallWorkflow llm;
    private final ToolCallWorkflow tool;
    private final Queue llmQueue;
    private final Queue toolQueue;

    public AgentRunCore(DBOS dbos, AgentWorkerClient client, LlmCallWorkflow llm, ToolCallWorkflow tool,
                        Queue llmQueue, Queue toolQueue) {
        this.dbos = dbos;
        this.client = client;
        this.fetcher = new ContextMaterialsFetcher(client);
        this.llm = llm;
        this.tool = tool;
        this.llmQueue = llmQueue;
        this.toolQueue = toolQueue;
    }

    /**
     * Fetch the agent's materials and compose the prompt + tool registry in one durable step.
     * The {@code profile} carries the assembly policy (see {@link ContextProfile}); {@code batch}
     * is the trigger batch for {@link ContextProfile#SYSTEM_TRIGGER}, null for dialogue.
     */
    public PreparedContext prepareContext(String agentId, ContextProfile profile, List<Trigger> batch) {
        return dbos.runStep(() -> ContextBuilder.build(profile, fetcher.fetch(agentId, profile, batch)),
                "prepare_context");
    }

    /** History slice plus the next free turn index (derived from the server's turn_idx). */
    record SessionHistory(List<AgentChatMessage> messages, int nextTurnIdx) {}

    private SessionHistory getSessionHistory(String agentId, String sessionPubId) {
        return dbos.runStep(() -> {
            GetHistoryResponse resp = client.getHistory(agentId, sessionPubId, HISTORY_WINDOW_MESSAGES);
            List<AgentChatMessage> messages = new ArrayList<>();
            for (HistoryMessage m : resp.getMessagesList()) {
                messages.add(MessageCodec.deserialize(m.getMessageJson().toByteArray()));
            }
            return new SessionHistory(messages, nextTurnIdx(resp));
        }, "get_session_history");
    }

    /**
     * Next free turn index: one past the highest persisted {@code turn_idx} — never the slice
     * size. {@code GetHistory} is capped at {@link #HISTORY_WINDOW_MESSAGES}, so once a session
     * outgrows the window, {@code size()} would collide with already-taken indices and the
     * server's idempotent insert (ON CONFLICT DO NOTHING) would silently drop every new message.
     */
    static int nextTurnIdx(GetHistoryResponse resp) {
        int max = -1;
        for (HistoryMessage m : resp.getMessagesList()) {
            max = Math.max(max, m.getTurnIdx());
        }
        return max + 1;
    }

    private List<Integer> appendSessionMessages(String agentId, String sessionPubId, String runId,
                                                int startingTurnIdx, List<AgentWorkerClient.AppendItem> items) {
        return dbos.runStep(
                () -> client.appendSessionMessages(agentId, sessionPubId, runId, startingTurnIdx, items),
                "append_session_messages");
    }

    /**
     * Run the agent loop body. With a {@code session}: history is restored, the initial request and
     * every turn are appended (idempotent by {@code (session, starting_turn_idx)}). Without one the
     * run is stateless. Per-turn progress goes to {@code output.progress} and the final answer to
     * {@code output.answer} either way. Only the user prompt is persisted — the system prompt is
     * injected fresh and never written to history.
     */
    public String run(String agentId, PreparedContext prepared, AgentChatMessage initialRequest,
                      SessionBinding session, OutboundPublisher output, String context, boolean drainControl) {
        ToolRegistry registry = prepared.registry();
        List<AgentChatMessage> history = new ArrayList<>();
        int[] nextTurnIdx = {0};

        if (session != null) {
            SessionHistory sessionHistory = getSessionHistory(agentId, session.sessionPubId());
            history = sessionHistory.messages();
            log.info("loaded {} historical message(s) for session; next turn idx {}",
                    history.size(), sessionHistory.nextTurnIdx());
            nextTurnIdx[0] = sessionHistory.nextTurnIdx();
            List<Integer> assigned = appendSessionMessages(agentId, session.sessionPubId(), session.runId(), nextTurnIdx[0],
                    List.of(new AgentWorkerClient.AppendItem(MessageKind.REQUEST,
                            MessageCodec.serialize(initialRequest), session.initialText(), session.triggerInputJson())));
            nextTurnIdx[0] += assigned.size();
        }

        AgentDispatcher dispatcher = new AgentDispatcher(dbos, llm, tool, llmQueue, toolQueue, agentId,
                session != null ? session.sessionPubId() : "", registry);

        final List<AgentChatMessage> historyForClosure = history;
        var onNewMessages = (java.util.function.Consumer<List<AgentChatMessage>>) newMsgs -> {
            if (session != null) {
                List<AgentWorkerClient.AppendItem> items = new ArrayList<>();
                for (AgentChatMessage m : newMsgs) {
                    boolean isResponse = m.role() == AgentChatMessage.Role.ASSISTANT;
                    List<String> displayNames = isResponse ? registry.displayNames(m) : List.of();
                    items.add(new AgentWorkerClient.AppendItem(
                            isResponse ? MessageKind.RESPONSE : MessageKind.REQUEST,
                            MessageCodec.serialize(m), MessageCodec.messageText(m, displayNames), null));
                }
                List<Integer> assigned = appendSessionMessages(agentId, session.sessionPubId(), session.runId(),
                        nextTurnIdx[0], items);
                nextTurnIdx[0] += assigned.size();
            }
            for (AgentChatMessage m : newMsgs) {
                if (m.role() == AgentChatMessage.Role.ASSISTANT) {
                    for (String line : MessageCodec.progressMessages(m, registry.displayNames(m))) {
                        output.progress(line);
                    }
                }
            }
        };

        // Hot memory notes ride alongside the user prompt but are never persisted (only the original
        // initial request was appended above); they are re-fetched each run, like the system prompt.
        AgentChatMessage modelRequest = RequestBuilder.withMemoryNotes(initialRequest, prepared.memoryNotes());

        // Steering (steer/interrupt policies) drains the control mailbox at each turn boundary;
        // an answer completed right before a steer folds in is delivered as an interim answer.
        SimpleAgent.Checkpointer checkpointer = drainControl ? (msgs, phase) -> drainControlTopic() : null;

        AgentRunner runner = new AgentRunner(dispatcher, dispatcher, registry.toolDefs(), MAX_AGENT_TURNS,
                context, onNewMessages, checkpointer, output::answer);
        String answer = runner.run(prepared.systemPrompt(), historyForClosure, modelRequest);
        log.info("LLM answered: {}", answer);
        output.answer(answer);
        return answer;
    }

    /**
     * Drain the control mailbox non-blockingly: fold every steer message into the run as a user
     * turn and request a graceful stop on the first interrupt. Called at each turn boundary when
     * the session policy is steer/interrupt.
     */
    private SimpleAgent.CheckpointResult drainControlTopic() {
        List<AgentChatMessage> injected = new ArrayList<>();
        boolean cancel = false;
        while (true) {
            Optional<String> raw = dbos.recv(Queues.CONTROL_TOPIC, Duration.ZERO);
            if (raw.isEmpty()) {
                break;
            }
            ControlSignal signal = ControlSignal.fromJson(raw.get());
            if (signal.isInterrupt()) {
                cancel = true;
                continue;
            }
            String text = steerText(signal.message());
            if (text != null && !text.isBlank()) {
                injected.add(AgentChatMessage.user(text));
            }
        }
        return new SimpleAgent.CheckpointResult(injected, cancel);
    }

    /** Extract the user text of a steered message: the channel's inbound text, else the trigger wrap. */
    private static String steerText(AgentMessage message) {
        if (message == null) {
            return null;
        }
        if (message.promptChannel() != null && message.inbound() != null
                && message.inbound().text() != null && !message.inbound().text().isEmpty()) {
            return message.inbound().text();
        }
        if (message.payload() != null) {
            return RequestBuilder.buildUntrustedTriggerRequest(message.payload());
        }
        return null;
    }

    /**
     * Report a terminal soft-abort to both sides. The user notice goes to {@code output.error} when
     * present; the system detail always reaches the backend via {@code WorkerControl.SendMessage}.
     */
    public void reportFailure(OutboundPublisher output, AgentRunAborted exc) {
        if (exc.userNotice() != null && !exc.userNotice().isEmpty()) {
            output.error(exc.userNotice());
        }
        client.sendMessage(WorkerMessageType.WORKER_MESSAGE_TYPE_ERROR, exc.systemDetail());
    }
}
