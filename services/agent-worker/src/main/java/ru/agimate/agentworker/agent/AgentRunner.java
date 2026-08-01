package ru.agimate.agentworker.agent;

import ru.agimate.agentworker.agent.error.AgentRunAborted;
import ru.agimate.agentworker.agent.error.EmptyAnswerExhausted;
import ru.agimate.agentworker.agent.error.ImitationLoopExhausted;
import ru.agimate.agentworker.agent.error.LlmCallError;
import ru.agimate.agentworker.agent.error.LlmResponseIncomplete;
import ru.agimate.agentworker.agent.error.MaxTurnsExceeded;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.ToolDef;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the message list and drives {@link SimpleAgent} to a final answer, mapping the loop's
 * expected terminal failures (max turns, LLM HTTP/API errors) to a single {@link AgentRunAborted}
 * the workflow reports in one place. Infra errors propagate for DBOS to retry. Pure glue — the
 * LLM/tool callables, the per-turn persistence hook, and the failure {@code context} are injected.
 */
public class AgentRunner {

    private final SimpleAgent.LlmCaller llmCaller;
    private final SimpleAgent.ToolDispatcher toolDispatcher;
    private final List<ToolDef> toolDefs;
    private final int maxTurns;
    private final String context;
    private final SimpleAgent.RunObserver observer;
    private final ResponseTemplates templates;

    public AgentRunner(SimpleAgent.LlmCaller llmCaller, SimpleAgent.ToolDispatcher toolDispatcher,
                       List<ToolDef> toolDefs, int maxTurns, String context,
                       SimpleAgent.RunObserver observer, ResponseTemplates templates) {
        this.llmCaller = llmCaller;
        this.toolDispatcher = toolDispatcher;
        this.toolDefs = toolDefs;
        this.maxTurns = maxTurns;
        this.context = context;
        this.observer = observer;
        this.templates = templates;
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

        SimpleAgent agent = new SimpleAgent(llmCaller, toolDispatcher, toolDefs, maxTurns, observer);
        try {
            return agent.run(messages);
        } catch (MaxTurnsExceeded e) {
            throw new AgentRunAborted(templates.maxTurns(),
                    "agent loop hit max_turns " + context + ": " + e.getMessage());
        } catch (EmptyAnswerExhausted e) {
            throw new AgentRunAborted(templates.emptyAnswer(),
                    "model returned an empty answer " + context + ": " + e.getMessage());
        } catch (ImitationLoopExhausted e) {
            throw new AgentRunAborted(templates.imitationError(),
                    "agent stuck imitating tool calls " + context + ": " + e.getMessage());
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
        }
    }
}
