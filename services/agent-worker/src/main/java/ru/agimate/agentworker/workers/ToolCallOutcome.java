package ru.agimate.agentworker.workers;

/**
 * Envelope returned by the {@code tool_call} workflow. The workflow never raises (so DBOS does not
 * log tool failures as workflow exceptions); the caller inspects {@code error} to surface failures
 * to the LLM. {@code outputJson} is the raw JSON the tool returned on success.
 */
public record ToolCallOutcome(String outputJson, String error) {

    public static ToolCallOutcome ok(String outputJson) {
        return new ToolCallOutcome(outputJson, null);
    }

    public static ToolCallOutcome error(String error) {
        return new ToolCallOutcome(null, error);
    }
}
