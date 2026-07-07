package ru.agimate.agentworker.workers;

/**
 * Persistence identity of a run: present → restore history and append every turn; null → a
 * stateless run. {@code sessionPubId} is the session; {@code runId} is the DBOS workflow id;
 * {@code initialText} and {@code triggerInputJson} annotate the first appended request.
 */
public record SessionBinding(String sessionPubId, String runId, String initialText, byte[] triggerInputJson) {
}
