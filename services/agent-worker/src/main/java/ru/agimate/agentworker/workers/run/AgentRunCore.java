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

    private static final int MAX_AGENT_TURNS = 30;
    private static final ObjectMapper PROMPT_MAPPER = new ObjectMapper();

    private final DBOS dbos;
    private final AgentWorkerClient client;
    private final ContextMaterialsFetcher fetcher;
    private final LlmCallWorkflow llm;
    private final ToolCallWorkflow tool;
    private final Queue llmQueue;
    private final Queue toolQueue;
    private final ResponseTemplates templates;

    public AgentRunCore(DBOS dbos, AgentWorkerClient client, LlmCallWorkflow llm, ToolCallWorkflow tool,
                        Queue llmQueue, Queue toolQueue, ResponseTemplates templates) {
        this.dbos = dbos;
        this.client = client;
        this.fetcher = new ContextMaterialsFetcher(client);
        this.llm = llm;
        this.tool = tool;
        this.llmQueue = llmQueue;
        this.toolQueue = toolQueue;
        this.templates = templates;
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
     * delivers). Only the rendered user prompt is durable — ephemeral blocks (memory notes) are
     * prepended to the model turn and are never part of the persisted dialogue.
     */
    public String run(String agentId, String runId, PreparedContext prepared, MessageLog messages,
                      String context) {
        ToolRegistry registry = prepared.registry();

        // Канонический журнал ходов (agent_run_turns): по записи на каждое сообщение, без капов и
        // для всех ранов. Обычный (не durable) вызов — проекция уже-durable данных, дедуп по
        // (run_id, turn_index) на бэке. Пишется рядом с канальной проекцией, не вместо неё.
        TurnLog turns = new TurnLog(client, agentId, runId);

        // Один наблюдатель событий рана — ран-обвязка единственный писатель backend-side-записей:
        //  • onStart — снимок стартового промпта (то, что ушло в первый LLM-вызов) в agent_runs.prompt,
        //    один раз перед циклом; дальнейшие ходы уже пишет TurnLog. First-write-wins на бэке.
        //  • onMessages — каждый ход отдельным вызовом (v2.1a): assistant с вызовами до dispatch →
        //    TOOL_CALL (+преамбула/thinking), затем tool-результаты → отдельная TOOL_RESULT-запись;
        //    историю следующих ранов бэк соберёт из этой пары в нативные tool_use/tool_result.
        //  • onUsage — учёт расхода токенов, best-effort, идемпотентно по call_id (реплей дедупит).
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
        AgentChatMessage modelRequest = withEphemeralPrefix(initialRequest, prepared.ephemeralUserPrefix());

        LlmCallDispatcher llmDispatcher = new LlmCallDispatcher(dbos, llm, llmQueue, agentId);
        ToolCallDispatcher toolDispatcher = new ToolCallDispatcher(dbos, tool, toolQueue, agentId,
                runId, registry);

        AgentRunner runner = new AgentRunner(llmDispatcher, toolDispatcher, registry.toolDefs(), MAX_AGENT_TURNS,
                context, observer, templates);
        String answer = runner.run(prepared.systemPrompt(), prepared.history(), modelRequest);
        messages.answer(answer);
        return answer;
    }

    /**
     * Репорт токенов вызова на бэк ({@code reportLlmUsage}). Пропускается без call_id (ключ
     * идемпотентности). Best-effort: сбой логируется и не валит ран — учёт это наблюдаемость.
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
     * Снимок стартового промпта рана ({@code savePrompt} → {@code agent_runs.prompt}): список
     * сообщений ровно как он ушёл в первый LLM-вызов (system + history + триггер с ephemeral).
     * Сериализуется как есть (вложения — ссылки, не байты). Best-effort: сбой логируется и не
     * валит ран — снимок это наблюдаемость. Бэк пишет first-write-wins, реплей не перезатирает.
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
     * message, if any — reference data goes ahead of the request the model must act on. The
     * ephemeral text is never persisted; only {@code initialRequest} rides into history.
     */
    private static AgentChatMessage withEphemeralPrefix(AgentChatMessage initialRequest, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return initialRequest;
        }
        String base = initialRequest.text() != null ? initialRequest.text() : "";
        // Вложения переносим на префиксированный ход — иначе «зрение» терялось бы при memory-notes.
        return AgentChatMessage.user(prefix + "\n\n" + base, initialRequest.parts());
    }

    /**
     * Report a terminal soft-abort to both sides. The user notice goes out as an ERROR dialogue
     * event when present; the system detail always reaches the backend via
     * {@code WorkerControl.SendMessage}.
     */
    public void reportFailure(MessageLog messages, AgentRunAborted exc) {
        // Best-effort: канал может быть недоступен, но системный репорт ниже уйти обязан.
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
