package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.workflow.Queue;
import lombok.extern.slf4j.Slf4j;
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

import java.util.List;
import java.util.function.Consumer;

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

    /** The run's dialogue-event writer; created here so the workflow shares one seq counter. */
    public MessageLog messageLog(String agentId, String triggerId) {
        return new MessageLog(dbos, client, agentId, triggerId);
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
     * Run the agent loop body: history and the user prompt come from {@code prepared}, per-turn
     * progress and the final answer are recorded via {@code messages} (the backend persists and
     * delivers). Only the rendered user prompt is durable — ephemeral blocks (memory notes) ride
     * alongside the model turn and are never part of the persisted dialogue.
     */
    public String run(String agentId, String triggerId, PreparedContext prepared, MessageLog messages,
                      String context) {
        ToolRegistry registry = prepared.registry();

        // Батч notify — один ход: [assistant] или [assistant, toolResults] (notify после dispatchAll),
        // поэтому результаты хода уже здесь — они уходят в структурную запись TOOL_CALL-строки.
        Consumer<List<AgentChatMessage>> onNewMessages = newMsgs -> {
            AgentChatMessage toolResults = newMsgs.stream()
                    .filter(m -> m.role() == AgentChatMessage.Role.TOOL)
                    .findFirst().orElse(null);
            for (AgentChatMessage m : newMsgs) {
                if (m.role() == AgentChatMessage.Role.ASSISTANT) {
                    for (MessageCodec.ProgressLine line
                            : MessageCodec.progressLines(m, registry.displayNames(m), toolResults)) {
                        messages.progress(line);
                    }
                }
            }
        };

        AgentChatMessage initialRequest = AgentChatMessage.user(prepared.userPrompt());
        AgentChatMessage modelRequest = withEphemeralSuffix(initialRequest, prepared.ephemeralUserSuffix());

        LlmCallDispatcher llmDispatcher = new LlmCallDispatcher(dbos, llm, llmQueue, agentId);
        ToolCallDispatcher toolDispatcher = new ToolCallDispatcher(dbos, tool, toolQueue, agentId,
                triggerId, registry);

        AgentRunner runner = new AgentRunner(llmDispatcher, toolDispatcher, registry.toolDefs(), MAX_AGENT_TURNS,
                context, onNewMessages);
        String answer = runner.run(prepared.systemPrompt(), prepared.history(), modelRequest);
        messages.answer(answer);
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

    /**
     * Report a terminal soft-abort to both sides. The user notice goes out as an ERROR dialogue
     * event when present; the system detail always reaches the backend via
     * {@code WorkerControl.SendMessage}.
     */
    public void reportFailure(MessageLog messages, AgentRunAborted exc) {
        if (exc.userNotice() != null && !exc.userNotice().isEmpty()) {
            messages.error(exc.userNotice());
        }
        // Ожидаемый терминальный исход (квота/лимит шагов/ошибка модели) — не инфра-сбой:
        // на бэк уходит информационным сообщением (INFO там), а не ERROR.
        sendSystemReport(WorkerMessageType.WORKER_MESSAGE_TYPE_MESSAGE, exc.systemDetail());
    }

    /** Пользовательский notice при неожиданной инфра-ошибке рана. */
    static final String INFRA_ERROR_NOTICE =
            "Извини, произошла внутренняя ошибка при обработке сообщения — попробуй ещё раз чуть позже.";

    /**
     * Best-effort report of an unexpected infra failure before the workflow goes to ERROR. The
     * likely cause is control-api being unreachable, so either send may fail as well — both are
     * swallowed so the original exception (rethrown by the caller) stays the recorded failure.
     */
    public void reportInfraFailure(MessageLog messages, String systemDetail) {
        try {
            messages.error(INFRA_ERROR_NOTICE);
        } catch (Exception e) {
            log.warn("failed to send infra-error notice to the channel: {}", e.getMessage());
        }
        sendSystemReport(WorkerMessageType.WORKER_MESSAGE_TYPE_ERROR, systemDetail);
    }

    /**
     * Системная деталь исхода рана на бэк durable-шагом (crash-replay не дублирует репорт) и
     * best-effort: падение самого репорта не должно перекрыть исход рана. {@code type} задаёт
     * уровень на бэке — MESSAGE (ожидаемый abort → INFO) или ERROR (инфра-сбой). Результат шага —
     * boolean: proto-ответ в чекпоинт класть нельзя.
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
