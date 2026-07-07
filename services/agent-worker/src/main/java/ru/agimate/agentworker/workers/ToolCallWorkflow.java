package ru.agimate.agentworker.workers;

/** One backend tool call per {@code tool_calls} queue item, so tool traffic gets its own concurrency. */
public interface ToolCallWorkflow {

    ToolCallOutcome toolCall(
            String connectorCode,
            String backendName,
            String argsJson,
            String toolCallId,
            String agentId,
            String agentSessionId,
            String identity);
}
