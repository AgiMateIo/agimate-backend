package ru.agimate.agentworker.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Assembles the message list and drives {@link SimpleAgent} to a final answer, mapping the loop's
 * expected terminal failures (max turns, LLM HTTP/API errors) to a single {@link AgentRunAborted}
 * the workflow reports in one place. Infra errors propagate for DBOS to retry. Pure glue — the
 * LLM/tool callables, the per-turn persistence hook, and the failure {@code context} are injected.
 */
public class AgentRunner {

    static final String MAX_TURNS_NOTICE =
            "Извини, не получилось завершить ответ — агент превысил лимит шагов.";
    static final String MODEL_ERROR_NOTICE =
            "Извини, произошла ошибка при обращении к модели — попробуй ещё раз.";
    static final String AUTH_ERROR_NOTICE =
            "Извини, не удаётся подключиться к модели — проверь настройки API-ключа.";

    private final SimpleAgent.LlmCaller llmCaller;
    private final SimpleAgent.ToolDispatcher toolDispatcher;
    private final List<ToolDef> toolDefs;
    private final int maxTurns;
    private final String context;
    private final Consumer<List<AgentChatMessage>> onNewMessages;
    private final SimpleAgent.Checkpointer checkpointer;

    public AgentRunner(SimpleAgent.LlmCaller llmCaller, SimpleAgent.ToolDispatcher toolDispatcher,
                       List<ToolDef> toolDefs, int maxTurns, String context,
                       Consumer<List<AgentChatMessage>> onNewMessages) {
        this(llmCaller, toolDispatcher, toolDefs, maxTurns, context, onNewMessages, null);
    }

    public AgentRunner(SimpleAgent.LlmCaller llmCaller, SimpleAgent.ToolDispatcher toolDispatcher,
                       List<ToolDef> toolDefs, int maxTurns, String context,
                       Consumer<List<AgentChatMessage>> onNewMessages, SimpleAgent.Checkpointer checkpointer) {
        this.llmCaller = llmCaller;
        this.toolDispatcher = toolDispatcher;
        this.toolDefs = toolDefs;
        this.maxTurns = maxTurns;
        this.context = context;
        this.onNewMessages = onNewMessages;
        this.checkpointer = checkpointer;
    }

    /**
     * Run the agent loop and return its final text. The system prompt is injected fresh into this
     * in-memory list every run and never persisted, so history truncation cannot drop it and a
     * changed spec takes effect next run. {@code history} sits between the system prompt and the new
     * {@code initialRequest}.
     */
    public String run(String systemPrompt, List<AgentChatMessage> history, AgentChatMessage initialRequest) {
        List<AgentChatMessage> messages = new ArrayList<>();
        messages.add(AgentChatMessage.system(systemPrompt));
        messages.addAll(history);
        messages.add(initialRequest);

        SimpleAgent agent = new SimpleAgent(llmCaller, toolDispatcher, toolDefs, maxTurns, onNewMessages, checkpointer);
        try {
            return agent.run(messages);
        } catch (MaxTurnsExceeded e) {
            throw new AgentRunAborted(MAX_TURNS_NOTICE,
                    "agent loop hit max_turns " + context + ": " + e.getMessage());
        } catch (LlmCallError e) {
            Integer status = e.statusCode();
            String userNotice;
            String prefix;
            if (status != null && (status == 401 || status == 403)) {
                userNotice = AUTH_ERROR_NOTICE;
                prefix = "LLM auth error (HTTP " + status + ")";
            } else if (status != null) {
                userNotice = MODEL_ERROR_NOTICE;
                prefix = "LLM HTTP error (HTTP " + status + ")";
            } else {
                userNotice = MODEL_ERROR_NOTICE;
                prefix = "LLM API error";
            }
            throw new AgentRunAborted(userNotice, prefix + " " + context + ": " + e.getMessage());
        }
    }
}
