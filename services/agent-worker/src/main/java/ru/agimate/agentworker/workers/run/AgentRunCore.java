package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.workflow.Queue;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.MessageKind;
import ru.agimate.agentworker.WorkerMessageType;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.error.AgentRunAborted;
import ru.agimate.agentworker.agent.AgentRunner;
import ru.agimate.agentworker.agent.MessageCodec;
import ru.agimate.agentworker.agent.SimpleAgent;
import ru.agimate.agentworker.agent.ToolRegistry;
import ru.agimate.agentworker.agent.context.ContextBuilder;
import ru.agimate.agentworker.grpc.AgentWorkerClient;
import ru.agimate.agentworker.workers.LlmCallWorkflow;
import ru.agimate.agentworker.workers.ToolCallWorkflow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shared agent-run core: one implementation of the invariant run body (prepare context, drive
 * {@link AgentRunner}, route output, report failures), parameterized by the per-entry policies that
 * differ (dialogue vs trigger scope; session vs stateless). Session persistence lives in
 * {@link SessionHistoryStore}, steering in {@link ControlMailbox}, and the LLM/tool dispatch in
 * {@link LlmCallDispatcher}/{@link ToolCallDispatcher}. Durable checkpoints are {@code dbos.runStep};
 * the LLM/tool calls are enqueued as child workflows.
 */
@Slf4j
public class AgentRunCore {

    private static final int MAX_AGENT_TURNS = 30;

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
     * Fetch the backend-assembled run context ({@code GetRunContext}) and render it into the
     * prompt + tool registry in one durable step. The assembly policy lives server-side
     * (ContextSpec); the worker only renders the blocks.
     */
    public PreparedContext prepareContext(String agentId, String triggerId) {
        return dbos.runStep(() -> ContextBuilder.build(fetcher.fetch(agentId, triggerId)),
                "prepare_context");
    }

    /**
     * Run the agent loop body. With a {@code session}: history is restored, the initial request and
     * every turn are appended via {@link SessionHistoryStore}; without one the run is stateless.
     * Per-turn progress goes to {@code output.progress} and the final answer to {@code output.answer}
     * either way. Only the user prompt is persisted — the system prompt is injected fresh and never
     * written to history.
     */
    public String run(String agentId, PreparedContext prepared, AgentChatMessage initialRequest,
                      SessionBinding session, OutboundPublisher output, String context, boolean drainControl) {
        ToolRegistry registry = prepared.registry();
        List<AgentChatMessage> history = new ArrayList<>();

        SessionHistoryStore store = null;
        if (session != null) {
            store = new SessionHistoryStore(dbos, client, agentId, session.sessionPubId(), session.runId());
            history = store.restore();
            store.append(List.of(new AgentWorkerClient.AppendItem(MessageKind.REQUEST,
                    MessageCodec.serialize(initialRequest), session.initialText(), session.triggerInputJson())));
        }

        final SessionHistoryStore historyStore = store;
        Consumer<List<AgentChatMessage>> onNewMessages = newMsgs -> {
            if (historyStore != null) {
                historyStore.append(toAppendItems(newMsgs, registry));
            }
            for (AgentChatMessage m : newMsgs) {
                if (m.role() == AgentChatMessage.Role.ASSISTANT) {
                    for (String line : MessageCodec.progressMessages(m, registry.displayNames(m))) {
                        output.progress(line);
                    }
                }
            }
        };

        // Ephemeral user blocks (memory notes и т.п.) ride alongside the user prompt but are never
        // persisted (only the original initial request was appended above); they are re-fetched
        // each run, like the system prompt.
        AgentChatMessage modelRequest = withEphemeralSuffix(initialRequest, prepared.ephemeralUserSuffix());

        // Steering (steer/interrupt policies) drains the control mailbox at each turn boundary;
        // an answer completed right before a steer folds in is delivered as an interim answer.
        ControlMailbox mailbox = new ControlMailbox(dbos);
        SimpleAgent.Checkpointer checkpointer = drainControl ? (msgs, phase) -> mailbox.drain() : null;

        String sessionPubId = session != null ? session.sessionPubId() : "";
        LlmCallDispatcher llmDispatcher = new LlmCallDispatcher(dbos, llm, llmQueue, agentId);
        ToolCallDispatcher toolDispatcher = new ToolCallDispatcher(dbos, tool, toolQueue, agentId, sessionPubId, registry);

        AgentRunner runner = new AgentRunner(llmDispatcher, toolDispatcher, registry.toolDefs(), MAX_AGENT_TURNS,
                context, onNewMessages, checkpointer, output::answer);
        String answer = runner.run(prepared.systemPrompt(), history, modelRequest);
        output.answer(answer);
        return answer;
    }

    /** Model-facing user turn: the initial request with the ephemeral suffix appended, if any. */
    private static AgentChatMessage withEphemeralSuffix(AgentChatMessage initialRequest, String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return initialRequest;
        }
        String base = initialRequest.text() != null ? initialRequest.text() : "";
        return AgentChatMessage.user(base + "\n\n" + suffix);
    }

    /** Map new loop messages to session-append items (response tool names rendered for the timeline). */
    private static List<AgentWorkerClient.AppendItem> toAppendItems(List<AgentChatMessage> newMsgs, ToolRegistry registry) {
        List<AgentWorkerClient.AppendItem> items = new ArrayList<>();
        for (AgentChatMessage m : newMsgs) {
            boolean isResponse = m.role() == AgentChatMessage.Role.ASSISTANT;
            List<String> displayNames = isResponse ? registry.displayNames(m) : List.of();
            items.add(new AgentWorkerClient.AppendItem(
                    isResponse ? MessageKind.RESPONSE : MessageKind.REQUEST,
                    MessageCodec.serialize(m), MessageCodec.messageText(m, displayNames), null));
        }
        return items;
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
