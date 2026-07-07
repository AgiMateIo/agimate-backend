package ru.agimate.agentworker.workers;

/** One backend tool call per {@code tool_calls} queue item, so tool traffic gets its own concurrency. */
public interface ToolCallWorkflow {

    Outcome toolCall(
            String connectorCode,
            String backendName,
            String argsJson,
            String toolCallId,
            String agentId,
            String agentSessionId,
            String identity);

    /**
     * The workflow never raises (so DBOS does not log tool failures as workflow exceptions); the
     * caller inspects {@code error} to surface failures to the LLM. {@code outputJson} is the raw
     * JSON the tool returned on success.
     */
    record Outcome(String outputJson, String error) {

        public static Outcome ok(String outputJson) {
            return new Outcome(outputJson, null);
        }

        public static Outcome error(String error) {
            return new Outcome(null, error);
        }
    }
}
