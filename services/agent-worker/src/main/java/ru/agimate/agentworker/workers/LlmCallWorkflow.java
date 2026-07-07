package ru.agimate.agentworker.workers;

import ru.agimate.agentworker.agent.AgentChatMessage;
import ru.agimate.agentworker.agent.ToolDef;

import java.util.List;

/** One model request per {@code llm_calls} queue item, so model traffic gets its own concurrency. */
public interface LlmCallWorkflow {

    LlmCallResult llmCall(List<AgentChatMessage> messages, List<ToolDef> toolDefs, String agentId);
}
